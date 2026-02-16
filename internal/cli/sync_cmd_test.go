package cli

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"kimen/internal/exitcode"
)

func TestCLI_RemoteLifecycle_JSONAndSyncCleanup(t *testing.T) {
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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}
	recipient := generateRecipient(t, identityPath)

	out, errBuf, err := runCLI([]string{
		"remote", "add", "origin",
		"--path", remoteDir,
		"--recipient", recipient,
		"--identity", identityPath,
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("remote add --json: %v (stderr=%s)", err, errBuf)
	}
	addResp := parseJSONMap(t, out)
	if addResp["action"] != "remote_add" || addResp["name"] != "origin" {
		t.Fatalf("unexpected remote add response: %#v", addResp)
	}

	out, errBuf, err = runCLI([]string{"remote", "list", "--json"}, nil)
	if err != nil {
		t.Fatalf("remote list --json: %v (stderr=%s)", err, errBuf)
	}
	listResp := parseJSONMap(t, out)
	if listResp["action"] != "remote_list" || listResp["count"] != float64(1) {
		t.Fatalf("unexpected remote list response: %#v", listResp)
	}

	out, errBuf, err = runCLI([]string{"sync", "push", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync push --json: %v (stderr=%s)", err, errBuf)
	}
	pushResp := parseJSONMap(t, out)
	if pushResp["action"] != "sync_push" || pushResp["remote"] != "origin" {
		t.Fatalf("unexpected sync push response: %#v", pushResp)
	}

	cfg := readConfig(t)
	if cfg.Sync["origin"].LastSeenRev == "" {
		t.Fatalf("expected sync baseline to be recorded: %#v", cfg.Sync)
	}

	out, errBuf, err = runCLI([]string{"remote", "rm", "origin", "--json"}, nil)
	if err != nil {
		t.Fatalf("remote rm --json: %v (stderr=%s)", err, errBuf)
	}
	rmResp := parseJSONMap(t, out)
	if rmResp["action"] != "remote_rm" || rmResp["name"] != "origin" {
		t.Fatalf("unexpected remote rm response: %#v", rmResp)
	}

	cfg = readConfig(t)
	if len(cfg.Remotes) != 0 {
		t.Fatalf("expected no remotes after remove: %#v", cfg.Remotes)
	}
	if _, ok := cfg.Sync["origin"]; ok {
		t.Fatalf("expected sync baseline to be removed for origin: %#v", cfg.Sync)
	}
}

func TestCLI_SyncPushConflictWhenRemoteChanged(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
	remoteBundle := filepath.Join(remoteDir, "vault.age")

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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}

	_, _, err = runCLI([]string{
		"bundle", "seal",
		"--vault", vaultPath,
		"--out", remoteBundle,
		"--recipient", recipient,
	}, nil)
	if err != nil {
		t.Fatalf("bundle seal remote mutate: %v", err)
	}

	_, errOut, err := runCLI([]string{"sync", "push", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync push conflict")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncConflict) {
		t.Fatalf("unexpected sync push conflict payload: %#v", errResp)
	}
}

func TestCLI_SyncPullRequiredForExistingRemote(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
	remoteBundle := filepath.Join(remoteDir, "vault.age")

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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("remote-value"))
	if err != nil {
		t.Fatalf("secret set remote-value: %v", err)
	}
	recipient := generateRecipient(t, identityPath)

	_, _, err = runCLI([]string{
		"bundle", "seal",
		"--vault", vaultPath,
		"--out", remoteBundle,
		"--recipient", recipient,
	}, nil)
	if err != nil {
		t.Fatalf("bundle seal: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("local-new"))
	if err != nil {
		t.Fatalf("secret set local-new: %v", err)
	}

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
	statusResp := parseJSONMap(t, out)
	if !jsonBool(statusResp, "needs_pull") || jsonBool(statusResp, "can_push") {
		t.Fatalf("unexpected sync status before pull: %#v", statusResp)
	}

	_, errOut, err := runCLI([]string{"sync", "push", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync push conflict before pull baseline")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncConflict) {
		t.Fatalf("unexpected sync push error payload: %#v", errResp)
	}

	out, errBuf, err = runCLI([]string{"sync", "pull", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync pull --json: %v (stderr=%s)", err, errBuf)
	}
	pullResp := parseJSONMap(t, out)
	if pullResp["action"] != "sync_pull" || pullResp["remote"] != "origin" {
		t.Fatalf("unexpected sync pull response: %#v", pullResp)
	}
	backupPath, _ := pullResp["backup_path"].(string)
	if backupPath == "" {
		t.Fatalf("expected backup_path in sync pull response: %#v", pullResp)
	}
	if _, err := os.Stat(backupPath); err != nil {
		t.Fatalf("expected backup file to exist at %q: %v", backupPath, err)
	}
	restoreBackupVault := withEnv(map[string]string{
		envVaultPath: backupPath,
	})
	backupValue, _, err := runCLI([]string{"secret", "get", "api_key", "--unsafe-stdout"}, nil)
	restoreBackupVault()
	if err != nil {
		t.Fatalf("secret get from backup vault: %v", err)
	}
	if backupValue != "local-new" {
		t.Fatalf("expected backup vault to contain pre-pull local value, got %q", backupValue)
	}

	out, _, err = runCLI([]string{"secret", "get", "api_key", "--unsafe-stdout"}, nil)
	if err != nil {
		t.Fatalf("secret get api_key: %v", err)
	}
	if out != "remote-value" {
		t.Fatalf("expected pulled remote value, got %q", out)
	}

	out, errBuf, err = runCLI([]string{"sync", "status", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync status --json after pull: %v (stderr=%s)", err, errBuf)
	}
	statusResp = parseJSONMap(t, out)
	if !jsonBool(statusResp, "in_sync") || !jsonBool(statusResp, "can_push") || jsonBool(statusResp, "needs_pull") {
		t.Fatalf("unexpected sync status after pull: %#v", statusResp)
	}
}

func TestCLI_RemoteAndSync_NonJSONErrorsWriteStderr(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
	remoteBundle := filepath.Join(remoteDir, "vault.age")

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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}
	recipient := generateRecipient(t, identityPath)

	_, _, err = runCLI([]string{
		"remote", "add", "origin",
		"--path", remoteDir,
		"--recipient", recipient,
		"--identity", identityPath,
	}, nil)
	if err != nil {
		t.Fatalf("remote add origin: %v", err)
	}

	_, errBuf, err := runCLI([]string{"remote", "add", "origin", "--path", remoteDir}, nil)
	if err == nil {
		t.Fatalf("expected remote duplicate add failure")
	}
	assertExitCode(t, err, exitcode.CodeRemoteFailed)
	if !strings.Contains(errBuf, "already exists") {
		t.Fatalf("expected duplicate-remote human error in stderr, got %q", errBuf)
	}

	_, _, err = runCLI([]string{"sync", "push", "--remote", "origin"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}
	_, _, err = runCLI([]string{
		"bundle", "seal",
		"--vault", vaultPath,
		"--out", remoteBundle,
		"--recipient", recipient,
	}, nil)
	if err != nil {
		t.Fatalf("bundle seal remote mutate: %v", err)
	}
	_, errBuf, err = runCLI([]string{"sync", "push", "--remote", "origin"}, nil)
	if err == nil {
		t.Fatalf("expected sync conflict")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	if !strings.Contains(errBuf, "remote changed") {
		t.Fatalf("expected sync conflict human error in stderr, got %q", errBuf)
	}
}

func TestCLI_SyncErrors_MissingRemoteMetadata_And_MultipleRemotes(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")

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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}

	_, _, err = runCLI([]string{"remote", "add", "origin", "--path", filepath.Join(dir, "remote-a")}, nil)
	if err != nil {
		t.Fatalf("remote add origin: %v", err)
	}

	_, errOut, err := runCLI([]string{"sync", "push", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync push failure without recipient")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected sync push error payload: %#v", errResp)
	}

	_, errOut, err = runCLI([]string{"sync", "pull", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync pull failure without identity")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	errResp = parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected sync pull error payload: %#v", errResp)
	}

	_, _, err = runCLI([]string{"remote", "add", "backup", "--path", filepath.Join(dir, "remote-b")}, nil)
	if err != nil {
		t.Fatalf("remote add backup: %v", err)
	}
	_, errOut, err = runCLI([]string{"sync", "status", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync status failure with multiple remotes and no --remote")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	errResp = parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected sync status error payload: %#v", errResp)
	}
}

func TestCLI_SyncPushFailsWhenRemoteLocked(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
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
	if err := os.MkdirAll(remoteDir, 0o700); err != nil {
		t.Fatalf("mkdir remote dir: %v", err)
	}
	if err := os.WriteFile(lockPath, []byte("held\n"), 0o600); err != nil {
		t.Fatalf("write lock file: %v", err)
	}

	_, errOut, err := runCLI([]string{"sync", "push", "--remote", "origin", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync push lock failure")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected sync push lock error payload: %#v", errResp)
	}
	if !strings.Contains(errResp["error"].(string), "remote push lock exists") {
		t.Fatalf("expected lock error message, got %#v", errResp)
	}
}

func TestCLI_SyncPushLockWaitSucceedsAfterRelease(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
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
	if err := os.MkdirAll(remoteDir, 0o700); err != nil {
		t.Fatalf("mkdir remote dir: %v", err)
	}
	if err := os.WriteFile(lockPath, []byte("held\n"), 0o600); err != nil {
		t.Fatalf("write lock file: %v", err)
	}

	go func() {
		time.Sleep(150 * time.Millisecond)
		_ = os.Remove(lockPath)
	}()

	out, errBuf, err := runCLI([]string{"sync", "push", "--remote", "origin", "--lock-wait", "2s", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync push --lock-wait --json: %v (stderr=%s)", err, errBuf)
	}
	pushResp := parseJSONMap(t, out)
	if pushResp["action"] != "sync_push" || pushResp["remote"] != "origin" {
		t.Fatalf("unexpected sync push response after lock wait: %#v", pushResp)
	}
	if _, err := os.Stat(lockPath); !os.IsNotExist(err) {
		t.Fatalf("expected lock file removed after successful push, stat err=%v", err)
	}
}

func TestCLI_SyncStatusAndConflicts_ReportLockState(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
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
	if err := os.MkdirAll(remoteDir, 0o700); err != nil {
		t.Fatalf("mkdir remote dir: %v", err)
	}
	if err := os.WriteFile(lockPath, []byte("pid=4242\ncreated_at=2026-01-01T00:00:00Z\nhost=ci-runner\nuser=agent\n"), 0o600); err != nil {
		t.Fatalf("write lock file: %v", err)
	}

	out, errBuf, err := runCLI([]string{"sync", "status", "--remote", "origin", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync status --json: %v (stderr=%s)", err, errBuf)
	}
	statusResp := parseJSONMap(t, out)
	if !jsonBool(statusResp, "has_lock") {
		t.Fatalf("expected has_lock=true in sync status: %#v", statusResp)
	}
	if statusResp["lock_path"] != lockPath {
		t.Fatalf("unexpected lock_path in sync status: %#v", statusResp)
	}
	if statusResp["lock_pid"] != "4242" {
		t.Fatalf("unexpected lock_pid in sync status: %#v", statusResp)
	}
	if statusResp["lock_created"] != "2026-01-01T00:00:00Z" {
		t.Fatalf("unexpected lock_created in sync status: %#v", statusResp)
	}
	if statusResp["lock_host"] != "ci-runner" {
		t.Fatalf("unexpected lock_host in sync status: %#v", statusResp)
	}
	if statusResp["lock_user"] != "agent" {
		t.Fatalf("unexpected lock_user in sync status: %#v", statusResp)
	}

	out, errBuf, err = runCLI([]string{"sync", "conflicts", "--remote", "origin", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync conflicts --json: %v (stderr=%s)", err, errBuf)
	}
	conflictsResp := parseJSONMap(t, out)
	if !jsonBool(conflictsResp, "has_lock") {
		t.Fatalf("expected has_lock=true in sync conflicts: %#v", conflictsResp)
	}
	if conflictsResp["lock_path"] != lockPath {
		t.Fatalf("unexpected lock_path in sync conflicts: %#v", conflictsResp)
	}

	if err := os.Remove(lockPath); err != nil {
		t.Fatalf("remove lock file: %v", err)
	}
	out, errBuf, err = runCLI([]string{"sync", "status", "--remote", "origin", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync status --json after lock remove: %v (stderr=%s)", err, errBuf)
	}
	statusResp = parseJSONMap(t, out)
	if jsonBool(statusResp, "has_lock") {
		t.Fatalf("expected has_lock=false after lock removal: %#v", statusResp)
	}
}

func TestCLI_SyncUnlock_RemovesLockWithSafetyChecks(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
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
	if err := os.MkdirAll(remoteDir, 0o700); err != nil {
		t.Fatalf("mkdir remote dir: %v", err)
	}
	if err := os.WriteFile(lockPath, []byte("held\n"), 0o600); err != nil {
		t.Fatalf("write lock file: %v", err)
	}

	_, errOut, err := runCLI([]string{"sync", "unlock", "--remote", "origin", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync unlock to require --yes")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected sync unlock error payload: %#v", errResp)
	}

	_, errOut, err = runCLI([]string{"sync", "unlock", "--remote", "origin", "--if-older-than", "5m", "--yes", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync unlock age guard failure")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	errResp = parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected sync unlock age-guard payload: %#v", errResp)
	}

	out, errBuf, err := runCLI([]string{"sync", "unlock", "--remote", "origin", "--yes", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync unlock --yes --json: %v (stderr=%s)", err, errBuf)
	}
	unlockResp := parseJSONMap(t, out)
	if unlockResp["action"] != "sync_unlock" || unlockResp["removed"] != true {
		t.Fatalf("unexpected sync unlock response: %#v", unlockResp)
	}
	if _, err := os.Stat(lockPath); !os.IsNotExist(err) {
		t.Fatalf("expected lock file removed by sync unlock, stat err=%v", err)
	}

	out, errBuf, err = runCLI([]string{"sync", "unlock", "--remote", "origin", "--yes", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync unlock missing lock --json: %v (stderr=%s)", err, errBuf)
	}
	unlockResp = parseJSONMap(t, out)
	if unlockResp["removed"] != false || unlockResp["reason"] != "lock_missing" {
		t.Fatalf("unexpected sync unlock missing-lock response: %#v", unlockResp)
	}
}

func TestCLI_RemoteGetAndSet_JSON(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
	newRemoteDir := filepath.Join(dir, "remote-new")

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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
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

	out, errBuf, err := runCLI([]string{"remote", "get", "origin", "--json"}, nil)
	if err != nil {
		t.Fatalf("remote get --json: %v (stderr=%s)", err, errBuf)
	}
	getResp := parseJSONMap(t, out)
	if getResp["action"] != "remote_get" || getResp["name"] != "origin" {
		t.Fatalf("unexpected remote get response: %#v", getResp)
	}

	_, _, err = runCLI([]string{"sync", "push", "--remote", "origin"}, nil)
	if err != nil {
		t.Fatalf("sync push: %v", err)
	}
	cfg := readConfig(t)
	if cfg.Sync["origin"].LastSeenRev == "" {
		t.Fatalf("expected sync baseline after push")
	}

	_, errOut, err := runCLI([]string{"remote", "set", "origin", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected remote set to fail without changed fields")
	}
	assertExitCode(t, err, exitcode.CodeRemoteFailed)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeRemoteFailed) {
		t.Fatalf("unexpected remote set error payload: %#v", errResp)
	}

	out, errBuf, err = runCLI([]string{"remote", "set", "origin", "--path", newRemoteDir, "--json"}, nil)
	if err != nil {
		t.Fatalf("remote set --json: %v (stderr=%s)", err, errBuf)
	}
	setResp := parseJSONMap(t, out)
	if setResp["action"] != "remote_set" || setResp["baseline_reset"] != true {
		t.Fatalf("unexpected remote set response: %#v", setResp)
	}
	cfg = readConfig(t)
	if cfg.Sync != nil {
		if _, ok := cfg.Sync["origin"]; ok {
			t.Fatalf("expected baseline reset when remote path changed: %#v", cfg.Sync)
		}
	}
}

func TestCLI_SyncConflictsAndResetBaseline(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	otherVault := filepath.Join(dir, "other.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
	remoteBundle := filepath.Join(remoteDir, "vault.age")

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
	_, _, err = runCLI([]string{"sync", "push", "--remote", "origin"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}

	restoreOther := withEnv(map[string]string{
		envVaultPath: otherVault,
	})
	_, _, err = runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		restoreOther()
		t.Fatalf("other vault init: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("remote-v2"))
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

	out, errBuf, err := runCLI([]string{"sync", "conflicts", "--remote", "origin", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync conflicts --json: %v (stderr=%s)", err, errBuf)
	}
	conflicts := parseJSONMap(t, out)
	if !jsonBool(conflicts, "has_conflict") || conflicts["reason"] != "remote_changed" {
		t.Fatalf("expected remote_changed conflict, got %#v", conflicts)
	}

	_, errOut, err := runCLI([]string{"sync", "reset-baseline", "--remote", "origin", "--to-remote", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected reset-baseline refusal without --yes")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected reset-baseline error payload: %#v", errResp)
	}

	out, errBuf, err = runCLI([]string{"sync", "reset-baseline", "--remote", "origin", "--to-remote", "--yes", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync reset-baseline --json: %v (stderr=%s)", err, errBuf)
	}
	resetResp := parseJSONMap(t, out)
	if resetResp["action"] != "sync_reset_baseline" || resetResp["mode"] != "to_remote" {
		t.Fatalf("unexpected reset-baseline response: %#v", resetResp)
	}

	out, errBuf, err = runCLI([]string{"sync", "conflicts", "--remote", "origin", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync conflicts after reset: %v (stderr=%s)", err, errBuf)
	}
	conflicts = parseJSONMap(t, out)
	if jsonBool(conflicts, "has_conflict") {
		t.Fatalf("expected no conflict after reset-baseline: %#v", conflicts)
	}

	_, _, err = runCLI([]string{"sync", "push", "--remote", "origin"}, nil)
	if err != nil {
		t.Fatalf("sync push after reset-baseline: %v", err)
	}
}

func TestCLI_SyncRestore_FromBackupAndNoBackup(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	manualBackup := filepath.Join(dir, "vault.manual.bak")

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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("v1"))
	if err != nil {
		t.Fatalf("secret set v1: %v", err)
	}

	b, err := os.ReadFile(vaultPath)
	if err != nil {
		t.Fatalf("read vault for manual backup: %v", err)
	}
	if err := os.WriteFile(manualBackup, b, 0o600); err != nil {
		t.Fatalf("write manual backup: %v", err)
	}

	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("v2"))
	if err != nil {
		t.Fatalf("secret set v2: %v", err)
	}

	out, errBuf, err := runCLI([]string{"sync", "restore", "--backup", manualBackup, "--json"}, nil)
	if err != nil {
		t.Fatalf("sync restore --json: %v (stderr=%s)", err, errBuf)
	}
	restoreResp := parseJSONMap(t, out)
	if restoreResp["action"] != "sync_restore" {
		t.Fatalf("unexpected sync restore response: %#v", restoreResp)
	}
	currentBackupPath, _ := restoreResp["current_backup_path"].(string)
	if currentBackupPath == "" {
		t.Fatalf("expected current_backup_path when restore runs with backup enabled: %#v", restoreResp)
	}
	if _, err := os.Stat(currentBackupPath); err != nil {
		t.Fatalf("expected current backup to exist: %v", err)
	}

	out, _, err = runCLI([]string{"secret", "get", "api_key", "--unsafe-stdout"}, nil)
	if err != nil {
		t.Fatalf("secret get after restore: %v", err)
	}
	if out != "v1" {
		t.Fatalf("expected restored value v1, got %q", out)
	}

	restoreCurrentBackupVault := withEnv(map[string]string{
		envVaultPath: currentBackupPath,
	})
	out, _, err = runCLI([]string{"secret", "get", "api_key", "--unsafe-stdout"}, nil)
	restoreCurrentBackupVault()
	if err != nil {
		t.Fatalf("secret get current backup vault: %v", err)
	}
	if out != "v2" {
		t.Fatalf("expected current backup to contain pre-restore value v2, got %q", out)
	}

	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("v3"))
	if err != nil {
		t.Fatalf("secret set v3: %v", err)
	}
	out, errBuf, err = runCLI([]string{"sync", "restore", "--backup", manualBackup, "--no-backup", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync restore --no-backup --json: %v (stderr=%s)", err, errBuf)
	}
	restoreResp = parseJSONMap(t, out)
	if _, ok := restoreResp["current_backup_path"]; ok {
		t.Fatalf("expected no current_backup_path with --no-backup: %#v", restoreResp)
	}
}

func generateRecipient(t *testing.T, identityPath string) string {
	t.Helper()
	out, errBuf, err := runCLI([]string{"bundle", "keygen", "--out", identityPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("bundle keygen --json: %v (stderr=%s)", err, errBuf)
	}
	resp := parseJSONMap(t, out)
	recipient, _ := resp["recipient"].(string)
	if recipient == "" {
		t.Fatalf("missing recipient in keygen response: %#v", resp)
	}
	return recipient
}

func parseJSONMap(t *testing.T, raw string) map[string]any {
	t.Helper()
	var payload map[string]any
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		t.Fatalf("json parse failed: %v (raw=%q)", err, raw)
	}
	return payload
}

func jsonBool(payload map[string]any, key string) bool {
	v, ok := payload[key]
	if !ok {
		return false
	}
	b, ok := v.(bool)
	return ok && b
}

func readConfig(t *testing.T) config {
	t.Helper()
	out, errBuf, err := runCLI([]string{"config", "show", "--pretty=false"}, nil)
	if err != nil {
		t.Fatalf("config show: %v (stderr=%s)", err, errBuf)
	}
	var c config
	if err := json.Unmarshal([]byte(out), &c); err != nil {
		t.Fatalf("config json parse: %v (raw=%q)", err, out)
	}
	return c
}
