package cli

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSessionServerUnlockPassphraseLockStop(t *testing.T) {
	dir := shortTempDir(t)
	socketPath := filepath.Join(dir, "session.sock")
	vaultPath := canonicalVaultPath(filepath.Join(dir, "vault.db"))

	done := make(chan error, 1)
	go func() {
		done <- serveSession(socketPath)
	}()
	waitForSessionServer(t, socketPath)

	resp, err := sendSessionRequest(socketPath, sessionRequest{
		Op:            "unlock",
		VaultPath:     vaultPath,
		PassphraseB64: base64.StdEncoding.EncodeToString([]byte("session-pass")),
		TTLSeconds:    60,
	})
	if err != nil {
		t.Fatalf("unlock request: %v", err)
	}
	if !resp.OK || resp.Locked {
		t.Fatalf("unexpected unlock response: %#v", resp)
	}

	resp, err = sendSessionRequest(socketPath, sessionRequest{
		Op:        "passphrase",
		VaultPath: vaultPath,
	})
	if err != nil {
		t.Fatalf("passphrase request: %v", err)
	}
	if !resp.OK {
		t.Fatalf("expected passphrase response ok, got %#v", resp)
	}
	pp, err := base64.StdEncoding.DecodeString(resp.PassphraseB64)
	if err != nil {
		t.Fatalf("decode passphrase: %v", err)
	}
	if string(pp) != "session-pass" {
		t.Fatalf("unexpected passphrase: %q", string(pp))
	}

	resp, err = sendSessionRequest(socketPath, sessionRequest{
		Op:        "passphrase",
		VaultPath: canonicalVaultPath(filepath.Join(dir, "other.db")),
	})
	if err != nil {
		t.Fatalf("wrong-vault passphrase request: %v", err)
	}
	if resp.OK {
		t.Fatalf("expected wrong-vault passphrase request to fail")
	}

	resp, err = sendSessionRequest(socketPath, sessionRequest{Op: "lock"})
	if err != nil {
		t.Fatalf("lock request: %v", err)
	}
	if !resp.OK || !resp.Locked {
		t.Fatalf("unexpected lock response: %#v", resp)
	}

	resp, err = sendSessionRequest(socketPath, sessionRequest{Op: "stop"})
	if err != nil {
		t.Fatalf("stop request: %v", err)
	}
	if !resp.OK {
		t.Fatalf("unexpected stop response: %#v", resp)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("serveSession returned error: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("session server did not stop")
	}
}

func TestResolvePassphraseForVaultUsesSessionAfterEnv(t *testing.T) {
	dir := shortTempDir(t)
	socketPath := filepath.Join(dir, "session.sock")
	vaultPath := canonicalVaultPath(filepath.Join(dir, "vault.db"))

	restore := withEnv(map[string]string{
		envSessionDir: dir,
		envConfigPath: filepath.Join(dir, "missing-config.json"),
		envPassphrase: "",
	})
	defer restore()

	done := make(chan error, 1)
	go func() {
		done <- serveSession(socketPath)
	}()
	waitForSessionServer(t, socketPath)
	defer func() {
		_, _ = sendSessionRequest(socketPath, sessionRequest{Op: "stop"})
		<-done
	}()

	resp, err := sendSessionRequest(socketPath, sessionRequest{
		Op:            "unlock",
		VaultPath:     vaultPath,
		PassphraseB64: base64.StdEncoding.EncodeToString([]byte("session-pass")),
		TTLSeconds:    60,
	})
	if err != nil {
		t.Fatalf("unlock request: %v", err)
	}
	if !resp.OK {
		t.Fatalf("unexpected unlock response: %#v", resp)
	}

	got, source, err := resolvePassphraseInfoForVault(vaultPath, "", false)
	if err != nil {
		t.Fatalf("resolve passphrase from session: %v", err)
	}
	if source != passphraseSourceSession {
		t.Fatalf("expected session source, got %q", source)
	}
	if string(got) != "session-pass" {
		t.Fatalf("unexpected session passphrase: %q", string(got))
	}

	restoreEnv := withEnv(map[string]string{envPassphrase: "env-pass"})
	defer restoreEnv()
	got, source, err = resolvePassphraseInfoForVault(vaultPath, "", false)
	if err != nil {
		t.Fatalf("resolve passphrase from env: %v", err)
	}
	if source != passphraseSourceEnv {
		t.Fatalf("expected env source, got %q", source)
	}
	if string(got) != "env-pass" {
		t.Fatalf("unexpected env passphrase: %q", string(got))
	}
}

func TestSessionStateExpiresPassphrase(t *testing.T) {
	st := &sessionState{}
	now := time.Unix(100, 0)
	vaultPath := canonicalVaultPath("/tmp/kimen-vault.db")

	st.unlock(vaultPath, []byte("session-pass"), time.Second, now)
	if _, _, ok := st.sessionPassphrase(vaultPath, now.Add(500*time.Millisecond)); !ok {
		t.Fatalf("expected session to be available before expiry")
	}
	if _, _, ok := st.sessionPassphrase(vaultPath, now.Add(2*time.Second)); ok {
		t.Fatalf("expected session to expire")
	}
}

func TestSessionDaemonEnvRemovesPassphrase(t *testing.T) {
	got := sessionDaemonEnv([]string{
		"PATH=/bin",
		envPassphrase + "=secret",
		envVaultPath + "=/tmp/vault.db",
	})
	for _, entry := range got {
		if entry == envPassphrase+"=secret" {
			t.Fatalf("daemon env retained %s", envPassphrase)
		}
	}
	if len(got) != 2 {
		t.Fatalf("unexpected daemon env: %#v", got)
	}
}

func waitForSessionServer(t *testing.T, socketPath string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		resp, err := sendSessionRequest(socketPath, sessionRequest{Op: "status"})
		if err == nil && resp.OK {
			return
		}
		lastErr = err
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("session server did not become ready: %v", lastErr)
}

func shortTempDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("/tmp", "kimen-session-test-*")
	if err != nil {
		t.Fatalf("short temp dir: %v", err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}
