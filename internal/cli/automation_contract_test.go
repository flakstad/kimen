package cli

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"kimen/internal/exitcode"
)

func TestCLI_Contract_SyncJSONShapes(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	otherVault := filepath.Join(dir, "other.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
	remoteBundle := filepath.Join(remoteDir, "vault.age")
	lockPath := filepath.Join(remoteDir, "vault.age.lock")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envConfigPath: configPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("local-v1"))
	if err != nil {
		t.Fatalf("secret set local-v1: %v", err)
	}
	recipient := generateRecipient(t, identityPath)
	_, _, err = runCLI([]string{
		"remote", "add", "origin",
		"--path", remoteDir,
		"--recipient", recipient,
		"--identity", identityPath,
	}, nil)
	if err != nil {
		t.Fatalf("remote add: %v", err)
	}

	out, errBuf, err := runCLI([]string{"sync", "status", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync status --json: %v (stderr=%s)", err, errBuf)
	}
	status := parseJSONMap(t, out)
	requireJSONKeys(t, status, "ok", "action", "remote", "has_remote", "has_lock", "has_local", "in_sync", "can_push", "needs_pull", "lock_blocks_push", "likely_stale", "lock_age_seconds")
	if status["action"] != "sync_status" {
		t.Fatalf("unexpected sync status action: %#v", status)
	}

	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("sync push: %v", err)
	}

	out, errBuf, err = runCLI([]string{"sync", "conflicts", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync conflicts --json: %v (stderr=%s)", err, errBuf)
	}
	conflicts := parseJSONMap(t, out)
	requireJSONKeys(t, conflicts, "ok", "action", "remote", "has_remote", "has_lock", "has_conflict", "lock_blocks_push", "likely_stale", "lock_age_seconds")
	if conflicts["action"] != "sync_conflicts" {
		t.Fatalf("unexpected sync conflicts action: %#v", conflicts)
	}

	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("local-v2"))
	if err != nil {
		t.Fatalf("secret set local-v2: %v", err)
	}
	out, errBuf, err = runCLI([]string{"sync", "push", "--dry-run", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync push --dry-run --json: %v (stderr=%s)", err, errBuf)
	}
	pushDry := parseJSONMap(t, out)
	requireJSONKeys(t, pushDry, "ok", "action", "remote", "dry_run", "has_local", "can_push")
	if pushDry["action"] != "sync_push_dry_run" || !jsonBool(pushDry, "dry_run") {
		t.Fatalf("unexpected sync push dry-run payload: %#v", pushDry)
	}

	if err := os.MkdirAll(remoteDir, 0o700); err != nil {
		t.Fatalf("mkdir remote dir: %v", err)
	}
	if err := os.WriteFile(lockPath, []byte("held\n"), 0o600); err != nil {
		t.Fatalf("write lock file: %v", err)
	}

	_, errOut, err := runCLI([]string{"sync", "conflicts", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict conflicts failure while lock is present")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	lockErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, lockErr, "ok", "error", "exit_code", "reason", "recommended_action")
	if lockErr["reason"] != "remote_lock_present" || lockErr["recommended_action"] != "wait_or_sync_unlock" {
		t.Fatalf("unexpected strict lock error payload: %#v", lockErr)
	}
	if err := os.Remove(lockPath); err != nil {
		t.Fatalf("remove lock file: %v", err)
	}

	restoreOther := withEnv(map[string]string{envVaultPath: otherVault})
	_, _, err = runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		restoreOther()
		t.Fatalf("other vault init: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("remote-v3"))
	if err != nil {
		restoreOther()
		t.Fatalf("other secret set: %v", err)
	}
	_, _, err = runCLI([]string{
		"bundle", "seal",
		"--vault", otherVault,
		"--out", remoteBundle,
		"--recipient", recipient,
	}, nil)
	restoreOther()
	if err != nil {
		t.Fatalf("bundle seal remote mutate: %v", err)
	}

	_, errOut, err = runCLI([]string{"sync", "conflicts", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict conflicts failure for remote_changed")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	conflictErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, conflictErr, "ok", "error", "exit_code", "reason", "recommended_action", "expected_rev", "actual_rev")
	if conflictErr["reason"] != "remote_changed" || conflictErr["recommended_action"] != "sync_pull" {
		t.Fatalf("unexpected strict conflict payload: %#v", conflictErr)
	}

	out, errBuf, err = runCLI([]string{"sync", "pull", "--dry-run", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync pull --dry-run --json: %v (stderr=%s)", err, errBuf)
	}
	pullDry := parseJSONMap(t, out)
	requireJSONKeys(t, pullDry, "ok", "action", "remote", "dry_run", "has_local", "would_backup", "in_sync")
	if pullDry["action"] != "sync_pull_dry_run" || !jsonBool(pullDry, "dry_run") {
		t.Fatalf("unexpected sync pull dry-run payload: %#v", pullDry)
	}
}

func TestCLI_Contract_DoctorJSONShape(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envConfigPath: configPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}
	if err := os.MkdirAll(remoteDir, 0o700); err != nil {
		t.Fatalf("mkdir remote dir: %v", err)
	}
	recipient := generateRecipient(t, identityPath)
	_, _, err = runCLI([]string{
		"remote", "add", "origin",
		"--type", "fs",
		"--path", remoteDir,
		"--recipient", recipient,
		"--identity", identityPath,
	}, nil)
	if err != nil {
		t.Fatalf("remote add: %v", err)
	}

	out, errBuf, err := runCLI([]string{"doctor", "--json"}, nil)
	if err != nil {
		t.Fatalf("doctor --json: %v (stderr=%s)", err, errBuf)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("doctor json parse: %v", err)
	}
	requireJSONKeys(t, report, "ok", "strict", "error_count", "warning_count", "checks")
	checksRaw, ok := report["checks"].([]any)
	if !ok || len(checksRaw) == 0 {
		t.Fatalf("expected non-empty doctor checks array, got %#v", report)
	}
	first, ok := checksRaw[0].(map[string]any)
	if !ok {
		t.Fatalf("expected check object in checks array, got %#v", checksRaw[0])
	}
	requireJSONKeys(t, first, "name", "status", "message")
	if _, ok := findDoctorCheckByName(report, "remote_origin_sync_state"); !ok {
		t.Fatalf("expected remote_origin_sync_state check in doctor report: %#v", report)
	}
}

func requireJSONKeys(t *testing.T, payload map[string]any, keys ...string) {
	t.Helper()
	for _, key := range keys {
		if _, ok := payload[key]; !ok {
			t.Fatalf("missing key %q in payload %#v", key, payload)
		}
	}
}

func findDoctorCheckByName(report map[string]any, name string) (map[string]any, bool) {
	checks, _ := report["checks"].([]any)
	for _, raw := range checks {
		check, _ := raw.(map[string]any)
		if check["name"] == name {
			return check, true
		}
	}
	return nil, false
}
