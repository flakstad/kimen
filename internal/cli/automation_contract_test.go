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
	requireJSONKeys(t, status, "ok", "action", "exit_code", "remote", "has_remote", "has_lock", "has_local", "in_sync", "can_push", "needs_pull", "lock_blocks_push", "likely_stale", "lock_age_seconds")
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
	requireJSONKeys(t, conflicts, "ok", "action", "exit_code", "remote", "has_remote", "has_lock", "has_conflict", "lock_blocks_push", "likely_stale", "lock_age_seconds")
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
	requireJSONKeys(t, pushDry, "ok", "action", "exit_code", "remote", "dry_run", "has_local", "can_push")
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
	requireJSONKeys(t, pullDry, "ok", "action", "exit_code", "remote", "dry_run", "has_local", "would_backup", "in_sync")
	if pullDry["action"] != "sync_pull_dry_run" || !jsonBool(pullDry, "dry_run") {
		t.Fatalf("unexpected sync pull dry-run payload: %#v", pullDry)
	}
}

func TestCLI_Contract_SyncInitJSONShape(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envConfigPath: configPath,
	})
	defer restore()

	recipient := generateRecipient(t, identityPath)
	out, errBuf, err := runCLI([]string{
		"sync", "init",
		"--remote", "origin",
		"--path", remoteDir,
		"--identity", identityPath,
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("sync init --json: %v (stderr=%s)", err, errBuf)
	}
	resp := parseJSONMap(t, out)
	requireJSONKeys(t, resp, "ok", "action", "exit_code", "remote", "created", "remote_config")
	if resp["action"] != "sync_init" || !jsonBool(resp, "created") {
		t.Fatalf("unexpected sync init payload: %#v", resp)
	}
	if !jsonBool(resp, "derived_recipient") || !jsonBool(resp, "check_ok") {
		t.Fatalf("expected derived recipient + successful check in sync init payload: %#v", resp)
	}
	if resp["recommended_action"] != "vault_init" {
		t.Fatalf("expected recommended_action=vault_init in sync init payload: %#v", resp)
	}
	if resp["next_command"] != "kimen vault init" {
		t.Fatalf("expected next_command for vault init in sync init payload: %#v", resp)
	}
	remoteCfg, ok := resp["remote_config"].(map[string]any)
	if !ok {
		t.Fatalf("expected remote_config object in sync init payload: %#v", resp)
	}
	if remoteCfg["recipient"] != recipient {
		t.Fatalf("expected derived recipient in remote_config: %#v", remoteCfg)
	}
}

func TestCLI_Contract_CoreJSONActions(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	mapPath := filepath.Join(dir, "plan.kmap")

	if err := os.WriteFile(mapPath, []byte("env API_KEY=api_key\n"), 0o600); err != nil {
		t.Fatalf("WriteFile(map): %v", err)
	}

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	out, errBuf, err := runCLI([]string{"version", "--json"}, nil)
	if err != nil {
		t.Fatalf("version --json: %v (stderr=%s)", err, errBuf)
	}
	versionResp := parseJSONMap(t, out)
	requireJSONKeys(t, versionResp, "ok", "action", "exit_code", "version", "raw_version", "commit", "date")
	if versionResp["action"] != "version" || !jsonBool(versionResp, "ok") {
		t.Fatalf("unexpected version payload: %#v", versionResp)
	}

	out, errBuf, err = runCLI([]string{"doctor", "--allow-missing-vault", "--json"}, nil)
	if err != nil {
		t.Fatalf("doctor --json: %v (stderr=%s)", err, errBuf)
	}
	doctorResp := parseJSONMap(t, out)
	requireJSONKeys(t, doctorResp, "ok", "action", "exit_code", "strict", "error_count", "warning_count", "checks")
	if doctorResp["action"] != "doctor" {
		t.Fatalf("unexpected doctor payload: %#v", doctorResp)
	}

	out, errBuf, err = runCLI([]string{"map", "lint", "--map", mapPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("map lint --json: %v (stderr=%s)", err, errBuf)
	}
	mapResp := parseJSONMap(t, out)
	requireJSONKeys(t, mapResp, "ok", "action", "exit_code", "mode", "error_count", "warning_count")
	if mapResp["action"] != "map_lint" {
		t.Fatalf("unexpected map lint payload: %#v", mapResp)
	}

	out, errBuf, err = runCLI([]string{"plan", "--map", mapPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("plan --json: %v (stderr=%s)", err, errBuf)
	}
	planResp := parseJSONMap(t, out)
	requireJSONKeys(t, planResp, "ok", "action", "exit_code", "mode", "env", "files", "env_paths", "cleanup")
	if planResp["action"] != "plan" || !jsonBool(planResp, "ok") {
		t.Fatalf("unexpected plan payload: %#v", planResp)
	}
}

func TestCLI_Contract_ProjectionAndPlanErrorReasons(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}

	_, errOut, err := runCLI([]string{"plan", "--mode", "nope", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected plan invalid mode failure")
	}
	assertExitCode(t, err, exitcode.CodePlanFailed)
	planErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, planErr, "ok", "error", "exit_code", "reason")
	if planErr["reason"] != "invalid_mode" {
		t.Fatalf("unexpected plan reason: %#v", planErr)
	}

	_, errOut, err = runCLI([]string{"envfile", "--env", "API_KEY=api_key", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected envfile missing out failure")
	}
	assertExitCode(t, err, exitcode.CodeEnvfileFailed)
	envfileErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, envfileErr, "ok", "error", "exit_code", "reason")
	if envfileErr["reason"] != "missing_out" {
		t.Fatalf("unexpected envfile reason: %#v", envfileErr)
	}

	_, errOut, err = runCLI([]string{"run", "--env", "API_KEY=missing", "--json", "--", "echo", "hello"}, nil)
	if err == nil {
		t.Fatalf("expected run missing secret failure")
	}
	assertExitCode(t, err, exitcode.CodeSecretNotFound)
	runErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, runErr, "ok", "error", "exit_code", "reason")
	if runErr["reason"] != "secret_not_found" {
		t.Fatalf("unexpected run reason: %#v", runErr)
	}

	_, errOut, err = runCLI([]string{"render", "--file", "cfg.txt=api_key", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected render missing target failure")
	}
	assertExitCode(t, err, exitcode.CodeProjectionFailed)
	renderErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, renderErr, "ok", "error", "exit_code", "reason")
	if renderErr["reason"] != "missing_render_target" {
		t.Fatalf("unexpected render reason: %#v", renderErr)
	}
}

func TestCLI_Contract_CoreCommandErrorReasons(t *testing.T) {
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

	_, errOut, err := runCLI([]string{"secret", "rm", "missing", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected secret rm missing failure")
	}
	assertExitCode(t, err, exitcode.CodeSecretNotFound)
	secretErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, secretErr, "ok", "error", "exit_code", "reason")
	if secretErr["reason"] != "secret_not_found" {
		t.Fatalf("unexpected secret reason: %#v", secretErr)
	}

	restoreVault := withEnv(map[string]string{envVaultPath: filepath.Join(dir, "missing.db")})
	_, errOut, err = runCLI([]string{"vault", "info", "--json"}, nil)
	restoreVault()
	if err == nil {
		t.Fatalf("expected vault info missing-vault failure")
	}
	assertExitCode(t, err, exitcode.CodeVaultNotFound)
	vaultErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, vaultErr, "ok", "error", "exit_code", "reason")
	if vaultErr["reason"] != "vault_not_found" {
		t.Fatalf("unexpected vault reason: %#v", vaultErr)
	}

	_, errOut, err = runCLI([]string{"bundle", "open", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected bundle open missing input failure")
	}
	assertExitCode(t, err, exitcode.CodeBundleFailed)
	bundleErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, bundleErr, "ok", "error", "exit_code", "reason")
	if bundleErr["reason"] != "missing_in" {
		t.Fatalf("unexpected bundle reason: %#v", bundleErr)
	}

	_, errOut, err = runCLI([]string{"config", "unlock", "set", "bogus", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected config unlock bogus failure")
	}
	assertExitCode(t, err, exitcode.CodeConfigFailed)
	configErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, configErr, "ok", "error", "exit_code", "reason")
	if configErr["reason"] != "unknown_unlock_method" {
		t.Fatalf("unexpected config reason: %#v", configErr)
	}

	_, errOut, err = runCLI([]string{"remote", "add", "bad/name", "--path", filepath.Join(dir, "remote"), "--json"}, nil)
	if err == nil {
		t.Fatalf("expected remote add invalid-name failure")
	}
	assertExitCode(t, err, exitcode.CodeRemoteFailed)
	remoteErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, remoteErr, "ok", "error", "exit_code", "reason")
	if remoteErr["reason"] != "invalid_remote_name" {
		t.Fatalf("unexpected remote reason: %#v", remoteErr)
	}

	_, errOut, err = runCLI([]string{"init", "ci-sync-gate", "--remote-type", "http", "--out", filepath.Join(dir, "wf.yml"), "--json"}, nil)
	if err == nil {
		t.Fatalf("expected init invalid remote-type failure")
	}
	assertExitCode(t, err, exitcode.CodeInitFailed)
	initErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, initErr, "ok", "error", "exit_code", "reason")
	if initErr["reason"] != "invalid_remote_type" {
		t.Fatalf("unexpected init reason: %#v", initErr)
	}
}

func TestCLI_Contract_NonSyncSuccessEnvelopesIncludeExitCode(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")
	identityPath := filepath.Join(dir, "bundle.agekey")
	bundlePath := filepath.Join(dir, "vault.age")
	outVaultPath := filepath.Join(dir, "opened.db")
	remoteDir := filepath.Join(dir, "remote")
	envfileOut := filepath.Join(dir, "app.env")
	renderDir := filepath.Join(dir, "render")
	initPrOut := filepath.Join(dir, "kimen-pr-safety.yml")
	initDeployOut := filepath.Join(dir, "kimen-deploy.yml")
	initSyncOut := filepath.Join(dir, "kimen-sync-gate.yml")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envConfigPath: configPath,
		envPassphrase: "pass",
	})
	defer restore()

	assertSuccessEnvelope := func(command []string, action string) map[string]any {
		t.Helper()
		out, errBuf, err := runCLI(command, nil)
		if err != nil {
			t.Fatalf("%s: %v (stderr=%s)", strings.Join(command, " "), err, errBuf)
		}
		resp := parseJSONMap(t, out)
		requireJSONKeys(t, resp, "ok", "action", "exit_code")
		if !jsonBool(resp, "ok") {
			t.Fatalf("expected ok=true for %s payload: %#v", strings.Join(command, " "), resp)
		}
		if resp["action"] != action {
			t.Fatalf("expected action=%s for %s payload: %#v", action, strings.Join(command, " "), resp)
		}
		if resp["exit_code"] != float64(0) {
			t.Fatalf("expected exit_code=0 for %s payload: %#v", strings.Join(command, " "), resp)
		}
		return resp
	}

	assertSuccessEnvelope([]string{"vault", "init", "--json"}, "vault_init")
	assertSuccessEnvelope([]string{"vault", "info", "--json"}, "vault_info")

	out, errBuf, err := runCLI([]string{"secret", "set", "api_key", "--stdin", "--json"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set with stdin: %v (stderr=%s)", err, errBuf)
	}
	resp := parseJSONMap(t, out)
	requireJSONKeys(t, resp, "ok", "action", "exit_code")
	if resp["action"] != "set" || resp["exit_code"] != float64(0) {
		t.Fatalf("unexpected secret set payload: %#v", resp)
	}
	assertSuccessEnvelope([]string{"secret", "list", "--json"}, "list")
	assertSuccessEnvelope([]string{"secret", "get", "api_key", "--unsafe-stdout", "--json"}, "get")
	assertSuccessEnvelope([]string{"secret", "mv", "api_key", "api_key_renamed", "--json"}, "mv")
	assertSuccessEnvelope([]string{"secret", "rm", "api_key_renamed", "--json"}, "rm")

	keygen := assertSuccessEnvelope([]string{"bundle", "keygen", "--out", identityPath, "--json"}, "bundle_keygen")
	recipient, _ := keygen["recipient"].(string)
	if recipient == "" {
		t.Fatalf("expected recipient in bundle keygen payload: %#v", keygen)
	}
	assertSuccessEnvelope([]string{"bundle", "recipient", "--identity", identityPath, "--json"}, "bundle_recipient")
	assertSuccessEnvelope([]string{"bundle", "seal", "--vault", vaultPath, "--out", bundlePath, "--recipient", recipient, "--json"}, "bundle_seal")
	assertSuccessEnvelope([]string{"bundle", "open", "--in", bundlePath, "--out-vault", outVaultPath, "--identity", identityPath, "--overwrite", "--json"}, "bundle_open")

	assertSuccessEnvelope([]string{"config", "path", "--json"}, "config_path")
	assertSuccessEnvelope([]string{"config", "unlock", "set", "env", "--json"}, "config_unlock_set")
	assertSuccessEnvelope([]string{"config", "unlock", "show", "--json"}, "config_unlock_show")
	assertSuccessEnvelope([]string{"config", "unlock", "clear", "--json"}, "config_unlock_clear")

	assertSuccessEnvelope([]string{"remote", "add", "origin", "--path", remoteDir, "--identity", identityPath, "--json"}, "remote_add")
	assertSuccessEnvelope([]string{"remote", "get", "origin", "--json"}, "remote_get")
	assertSuccessEnvelope([]string{"remote", "set", "origin", "--path", filepath.Join(remoteDir, "next"), "--json"}, "remote_set")
	assertSuccessEnvelope([]string{"remote", "list", "--json"}, "remote_list")
	assertSuccessEnvelope([]string{"remote", "rm", "origin", "--json"}, "remote_rm")

	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set for envfile/render: %v", err)
	}
	assertSuccessEnvelope([]string{"envfile", "--env", "API_KEY=api_key", "--out", envfileOut, "--json"}, "envfile")
	assertSuccessEnvelope([]string{"render", "--dir", renderDir, "--file", "cfg.txt=api_key", "--json"}, "render")

	assertSuccessEnvelope([]string{"init", "ci-pr-safety", "--out", initPrOut, "--json"}, "init_ci_pr_safety")
	assertSuccessEnvelope([]string{"init", "ci-deploy", "--out", initDeployOut, "--json"}, "init_ci_deploy")
	assertSuccessEnvelope([]string{"init", "ci-sync-gate", "--out", initSyncOut, "--json"}, "init_ci_sync_gate")

	assertSuccessEnvelope([]string{"version", "--json"}, "version")
}

func TestCLI_Contract_StrictGateSequence(t *testing.T) {
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}

	out, errBuf, err := runCLI([]string{"doctor", "--strict", "--json"}, nil)
	if err != nil {
		t.Fatalf("doctor --strict --json (healthy): %v (stderr=%s)", err, errBuf)
	}
	doctor := parseJSONMap(t, out)
	if !jsonBool(doctor, "ok") || !jsonBool(doctor, "strict") {
		t.Fatalf("expected strict doctor success in healthy state: %#v", doctor)
	}

	out, errBuf, err = runCLI([]string{"sync", "status", "--strict", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync status --strict --json (healthy): %v (stderr=%s)", err, errBuf)
	}
	status := parseJSONMap(t, out)
	requireJSONKeys(t, status, "action", "exit_code", "remote", "can_push", "needs_pull", "recommended_action")
	if status["action"] != "sync_status" {
		t.Fatalf("unexpected strict status payload: %#v", status)
	}

	out, errBuf, err = runCLI([]string{"sync", "conflicts", "--strict", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync conflicts --strict --json (healthy): %v (stderr=%s)", err, errBuf)
	}
	conflicts := parseJSONMap(t, out)
	requireJSONKeys(t, conflicts, "action", "exit_code", "remote", "has_conflict", "recommended_action")
	if conflicts["action"] != "sync_conflicts" {
		t.Fatalf("unexpected strict conflicts payload: %#v", conflicts)
	}

	out, errBuf, err = runCLI([]string{"sync", "pull", "--dry-run", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync pull --dry-run --json (healthy): %v (stderr=%s)", err, errBuf)
	}
	pullDry := parseJSONMap(t, out)
	if pullDry["action"] != "sync_pull_dry_run" || !jsonBool(pullDry, "dry_run") {
		t.Fatalf("unexpected sync pull dry-run payload: %#v", pullDry)
	}

	out, errBuf, err = runCLI([]string{"sync", "push", "--dry-run", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync push --dry-run --json (healthy): %v (stderr=%s)", err, errBuf)
	}
	pushDry := parseJSONMap(t, out)
	if pushDry["action"] != "sync_push_dry_run" || !jsonBool(pushDry, "dry_run") {
		t.Fatalf("unexpected sync push dry-run payload: %#v", pushDry)
	}

	if err := os.WriteFile(lockPath, []byte("held\n"), 0o600); err != nil {
		t.Fatalf("write lock file: %v", err)
	}
	_, errOut, err := runCLI([]string{"sync", "status", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict status to fail when lock is present")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	statusErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, statusErr, "exit_code", "reason", "recommended_action")
	if statusErr["reason"] != "remote_lock_present" || statusErr["recommended_action"] != "wait_or_sync_unlock" {
		t.Fatalf("unexpected strict status lock payload: %#v", statusErr)
	}

	_, errOut, err = runCLI([]string{"sync", "conflicts", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict conflicts to fail when lock is present")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	conflictsErr := parseJSONMap(t, errOut)
	requireJSONKeys(t, conflictsErr, "exit_code", "reason", "recommended_action")
	if conflictsErr["reason"] != "remote_lock_present" || conflictsErr["recommended_action"] != "wait_or_sync_unlock" {
		t.Fatalf("unexpected strict conflicts lock payload: %#v", conflictsErr)
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

	_, errOut, err = runCLI([]string{"sync", "status", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict status to fail on remote_changed")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	statusErr = parseJSONMap(t, errOut)
	requireJSONKeys(t, statusErr, "exit_code", "reason", "recommended_action", "expected_rev", "actual_rev")
	if statusErr["reason"] != "remote_changed" || statusErr["recommended_action"] != "sync_pull" {
		t.Fatalf("unexpected strict status conflict payload: %#v", statusErr)
	}

	_, errOut, err = runCLI([]string{"sync", "conflicts", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict conflicts to fail on remote_changed")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	conflictsErr = parseJSONMap(t, errOut)
	requireJSONKeys(t, conflictsErr, "exit_code", "reason", "recommended_action", "expected_rev", "actual_rev")
	if conflictsErr["reason"] != "remote_changed" || conflictsErr["recommended_action"] != "sync_pull" {
		t.Fatalf("unexpected strict conflicts conflict payload: %#v", conflictsErr)
	}

	out, errBuf, err = runCLI([]string{"doctor", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict doctor failure when remote sync state drift exists")
	}
	assertExitCode(t, err, exitcode.CodeDoctorFailed)
	if errBuf != "" {
		t.Fatalf("doctor --strict --json should emit report on stdout (stderr=%q)", errBuf)
	}
	doctor = parseJSONMap(t, out)
	if jsonBool(doctor, "ok") {
		t.Fatalf("expected doctor report ok=false in drift state: %#v", doctor)
	}
	check, ok := findDoctorCheckByName(doctor, "remote_origin_sync_state")
	if !ok {
		t.Fatalf("expected remote_origin_sync_state check in doctor report: %#v", doctor)
	}
	statusValue, _ := check["status"].(string)
	if statusValue == doctorStatusOK {
		t.Fatalf("expected non-ok sync_state check under drift, got %#v", check)
	}
}

func TestCLI_Contract_SyncPreflightJSONShape(t *testing.T) {
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}

	out, errBuf, err := runCLI([]string{"sync", "preflight", "--strict", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync preflight --strict --json: %v (stderr=%s)", err, errBuf)
	}
	report := parseJSONMap(t, out)
	requireJSONKeys(t, report, "ok", "action", "strict", "exit_code", "check_count", "failed_count", "checks")
	if report["action"] != "sync_preflight" {
		t.Fatalf("unexpected preflight action: %#v", report)
	}
	if !jsonBool(report, "ok") || !jsonBool(report, "strict") {
		t.Fatalf("expected strict preflight success: %#v", report)
	}
	if report["exit_code"] != float64(0) || report["failed_count"] != float64(0) {
		t.Fatalf("unexpected strict preflight success codes: %#v", report)
	}
	checks, _ := report["checks"].([]any)
	if len(checks) != 5 {
		t.Fatalf("expected 5 preflight checks, got %d (%#v)", len(checks), report)
	}
}

func TestCLI_Contract_SyncPreflightStrictFailureCodes(t *testing.T) {
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}

	if err := os.WriteFile(lockPath, []byte("held\n"), 0o600); err != nil {
		t.Fatalf("write lock file: %v", err)
	}
	out, errBuf, err := runCLI([]string{"sync", "preflight", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync preflight strict failure with lock")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	if strings.TrimSpace(errBuf) != "" {
		t.Fatalf("expected preflight failure report on stdout only, got stderr=%q", errBuf)
	}
	lockReport := parseJSONMap(t, out)
	requireJSONKeys(t, lockReport, "ok", "action", "exit_code", "failed_check", "recommended_action", "failed_checks", "checks")
	if lockReport["exit_code"] != float64(exitcode.CodeSyncFailed) {
		t.Fatalf("unexpected preflight lock exit code: %#v", lockReport)
	}
	if lockReport["failed_check"] != "sync_status" || lockReport["recommended_action"] != "wait_or_sync_unlock" {
		t.Fatalf("unexpected lock preflight summary fields: %#v", lockReport)
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

	out, errBuf, err = runCLI([]string{"sync", "preflight", "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync preflight strict conflict failure")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	if strings.TrimSpace(errBuf) != "" {
		t.Fatalf("expected preflight conflict report on stdout only, got stderr=%q", errBuf)
	}
	conflictReport := parseJSONMap(t, out)
	requireJSONKeys(t, conflictReport, "ok", "action", "exit_code", "failed_check", "recommended_action", "failed_checks", "checks")
	if conflictReport["exit_code"] != float64(exitcode.CodeSyncConflict) {
		t.Fatalf("unexpected preflight conflict exit code: %#v", conflictReport)
	}
	if conflictReport["recommended_action"] != "sync_pull" {
		t.Fatalf("unexpected conflict preflight recommended action: %#v", conflictReport)
	}
	failedChecks := jsonStringSlice(conflictReport, "failed_checks")
	if !containsString(failedChecks, "sync_status") {
		t.Fatalf("expected sync_status in failed checks for conflict preflight: %#v", conflictReport)
	}
}

func TestCLI_Contract_SyncPreflightCheckSelection(t *testing.T) {
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}

	out, errBuf, err := runCLI([]string{"sync", "preflight", "--strict", "--json", "--only", "doctor"}, nil)
	if err != nil {
		t.Fatalf("sync preflight only doctor: %v (stderr=%s)", err, errBuf)
	}
	report := parseJSONMap(t, out)
	requireJSONKeys(t, report, "ok", "check_count", "checks")
	if report["check_count"] != float64(1) {
		t.Fatalf("expected one check for only doctor, got %#v", report)
	}
	checkNames := preflightCheckNames(report)
	if len(checkNames) != 1 || checkNames[0] != "doctor" {
		t.Fatalf("unexpected only doctor checks: %#v", checkNames)
	}

	out, errBuf, err = runCLI([]string{"sync", "preflight", "--strict", "--json", "--only", "status"}, nil)
	if err != nil {
		t.Fatalf("sync preflight only status alias: %v (stderr=%s)", err, errBuf)
	}
	report = parseJSONMap(t, out)
	if report["check_count"] != float64(1) {
		t.Fatalf("expected one check for only status, got %#v", report)
	}
	checkNames = preflightCheckNames(report)
	if len(checkNames) != 1 || checkNames[0] != "sync_status" {
		t.Fatalf("unexpected only status checks: %#v", checkNames)
	}

	out, errBuf, err = runCLI([]string{"sync", "preflight", "--strict", "--json", "--skip", "doctor", "--skip", "push"}, nil)
	if err != nil {
		t.Fatalf("sync preflight skip doctor/push: %v (stderr=%s)", err, errBuf)
	}
	report = parseJSONMap(t, out)
	if report["check_count"] != float64(3) {
		t.Fatalf("expected three checks when skipping doctor/push, got %#v", report)
	}
	checkNames = preflightCheckNames(report)
	if containsString(checkNames, "doctor") || containsString(checkNames, "sync_push_dry_run") {
		t.Fatalf("expected doctor/push to be skipped, got %#v", checkNames)
	}
}

func TestCLI_Contract_SyncPreflightCheckSelectionValidation(t *testing.T) {
	out, errOut, err := runCLI([]string{"sync", "preflight", "--json", "--only", "nope"}, nil)
	if err == nil {
		t.Fatalf("expected selection validation error for unknown check")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	if strings.TrimSpace(out) != "" {
		t.Fatalf("expected no stdout for selection validation failure, got %q", out)
	}
	errPayload := parseJSONMap(t, errOut)
	requireJSONKeys(t, errPayload, "ok", "error", "exit_code")
	msg, _ := errPayload["error"].(string)
	if !strings.Contains(msg, "unknown preflight check") {
		t.Fatalf("unexpected unknown-check error payload: %#v", errPayload)
	}

	out, errOut, err = runCLI([]string{"sync", "preflight", "--json", "--only", "doctor", "--skip", "doctor"}, nil)
	if err == nil {
		t.Fatalf("expected selection validation error for empty check set")
	}
	assertExitCode(t, err, exitcode.CodeSyncFailed)
	if strings.TrimSpace(out) != "" {
		t.Fatalf("expected no stdout for empty-selection validation failure, got %q", out)
	}
	errPayload = parseJSONMap(t, errOut)
	requireJSONKeys(t, errPayload, "ok", "error", "exit_code")
	msg, _ = errPayload["error"].(string)
	if !strings.Contains(msg, "no preflight checks selected") {
		t.Fatalf("unexpected empty-selection error payload: %#v", errPayload)
	}
}

func TestCLI_Contract_SyncOrchestrationJSONShape(t *testing.T) {
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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("v1"))
	if err != nil {
		t.Fatalf("secret set v1: %v", err)
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

	out, errBuf, err := runCLI([]string{"sync", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync --json: %v (stderr=%s)", err, errBuf)
	}
	report := parseJSONMap(t, out)
	requireJSONKeys(t, report, "ok", "action", "mode", "decision", "exit_code", "steps", "status")
	if report["action"] != "sync" || report["mode"] != "apply" {
		t.Fatalf("unexpected sync orchestration action/mode: %#v", report)
	}
	if report["exit_code"] != float64(0) || !jsonBool(report, "ok") {
		t.Fatalf("unexpected sync orchestration success codes: %#v", report)
	}
}

func TestCLI_Contract_SyncOrchestrationReconcileDecision(t *testing.T) {
	dir := t.TempDir()
	identityPath := filepath.Join(dir, "sync.agekey")
	remoteDir := filepath.Join(dir, "remote")
	vaultA := filepath.Join(dir, "vault-a.db")
	configA := filepath.Join(dir, "config-a.json")
	vaultB := filepath.Join(dir, "vault-b.db")
	configB := filepath.Join(dir, "config-b.json")

	restoreA := withEnv(map[string]string{
		envVaultPath:  vaultA,
		envConfigPath: configA,
		envPassphrase: "pass",
	})
	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		restoreA()
		t.Fatalf("vault init (actor A): %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("a1"))
	if err != nil {
		restoreA()
		t.Fatalf("secret set api_key a1 (actor A): %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "db_pw", "--stdin"}, strings.NewReader("p1"))
	if err != nil {
		restoreA()
		t.Fatalf("secret set db_pw p1 (actor A): %v", err)
	}
	recipient := generateRecipient(t, identityPath)
	_, _, err = runCLI([]string{
		"remote", "add", "origin",
		"--path", remoteDir,
		"--recipient", recipient,
		"--identity", identityPath,
	}, nil)
	if err != nil {
		restoreA()
		t.Fatalf("remote add (actor A): %v", err)
	}
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		restoreA()
		t.Fatalf("initial sync push (actor A): %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("a2-local"))
	restoreA()
	if err != nil {
		t.Fatalf("secret set api_key a2-local (actor A): %v", err)
	}

	restoreB := withEnv(map[string]string{
		envVaultPath:  vaultB,
		envConfigPath: configB,
		envPassphrase: "pass",
	})
	_, _, err = runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		restoreB()
		t.Fatalf("vault init (actor B): %v", err)
	}
	_, _, err = runCLI([]string{
		"remote", "add", "origin",
		"--path", remoteDir,
		"--recipient", recipient,
		"--identity", identityPath,
	}, nil)
	if err != nil {
		restoreB()
		t.Fatalf("remote add (actor B): %v", err)
	}
	_, _, err = runCLI([]string{"sync", "pull"}, nil)
	if err != nil {
		restoreB()
		t.Fatalf("sync pull (actor B): %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "db_pw", "--stdin"}, strings.NewReader("p2-remote"))
	if err != nil {
		restoreB()
		t.Fatalf("secret set db_pw p2-remote (actor B): %v", err)
	}
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	restoreB()
	if err != nil {
		t.Fatalf("sync push (actor B): %v", err)
	}

	restoreA = withEnv(map[string]string{
		envVaultPath:  vaultA,
		envConfigPath: configA,
		envPassphrase: "pass",
	})
	out, errBuf, err := runCLI([]string{"sync", "--check", "--json"}, nil)
	if err != nil {
		restoreA()
		t.Fatalf("sync --check --json (actor A): %v (stderr=%s)", err, errBuf)
	}
	checkReport := parseJSONMap(t, out)
	requireJSONKeys(t, checkReport, "ok", "action", "decision", "exit_code")
	if checkReport["decision"] != "would_pull_reconcile" || !jsonBool(checkReport, "ok") {
		restoreA()
		t.Fatalf("unexpected sync orchestration check decision payload: %#v", checkReport)
	}

	out, errBuf, err = runCLI([]string{"sync", "--json"}, nil)
	if err != nil {
		restoreA()
		t.Fatalf("sync --json (actor A): %v (stderr=%s)", err, errBuf)
	}
	report := parseJSONMap(t, out)
	requireJSONKeys(t, report, "ok", "action", "mode", "decision", "exit_code", "steps", "status")
	if report["decision"] != "pull_reconcile" || !jsonBool(report, "ok") {
		restoreA()
		t.Fatalf("unexpected sync orchestration reconcile payload: %#v", report)
	}
	steps, _ := report["steps"].([]any)
	if len(steps) == 0 {
		restoreA()
		t.Fatalf("expected non-empty steps in orchestration reconcile payload: %#v", report)
	}
	last, _ := steps[len(steps)-1].(map[string]any)
	if last["name"] != "sync_pull_reconcile" {
		restoreA()
		t.Fatalf("expected last step sync_pull_reconcile, got %#v", last)
	}
	restoreA()
}

func TestCLI_Contract_SyncOrchestrationConflictFailureOnStdout(t *testing.T) {
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("local-v2"))
	if err != nil {
		t.Fatalf("secret set local-v2: %v", err)
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

	out, errBuf, err := runCLI([]string{"sync", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected orchestration sync conflict failure")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	if strings.TrimSpace(errBuf) != "" {
		t.Fatalf("expected orchestration sync conflict report on stdout only, got stderr=%q", errBuf)
	}
	report := parseJSONMap(t, out)
	requireJSONKeys(t, report, "ok", "action", "decision", "exit_code", "reason")
	if jsonBool(report, "ok") || report["decision"] != "blocked" {
		t.Fatalf("unexpected sync orchestration conflict payload: %#v", report)
	}
	if report["exit_code"] != float64(exitcode.CodeSyncConflict) || report["reason"] != "overlapping_changes" {
		t.Fatalf("unexpected sync orchestration conflict fields: %#v", report)
	}
}

func TestCLI_Contract_SyncChangesJSONShape(t *testing.T) {
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
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("v1"))
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
		t.Fatalf("sync push: %v", err)
	}

	out, errBuf, err := runCLI([]string{"sync", "changes", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync changes --json: %v (stderr=%s)", err, errBuf)
	}
	report := parseJSONMap(t, out)
	requireJSONKeys(t, report, "ok", "action", "remote", "has_baseline", "has_remote", "has_local", "can_reconcile")
	if report["action"] != "sync_changes" || !jsonBool(report, "ok") {
		t.Fatalf("unexpected sync changes payload: %#v", report)
	}
}

func TestCLI_Contract_SyncResolveJSONShape(t *testing.T) {
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("local-v2"))
	if err != nil {
		t.Fatalf("secret set local-v2: %v", err)
	}

	restoreOther := withEnv(map[string]string{
		envVaultPath:  otherVault,
		envPassphrase: "pass",
	})
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

	out, errBuf, err := runCLI([]string{"sync", "resolve", "--take", "remote", "--key", "api_key", "--json"}, nil)
	if err != nil {
		t.Fatalf("sync resolve --json: %v (stderr=%s)", err, errBuf)
	}
	report := parseJSONMap(t, out)
	requireJSONKeys(t, report, "ok", "action", "remote", "remote_rev", "last_seen_rev", "take", "keys", "resolved_count", "remaining_conflict_count", "recommended_action")
	if report["action"] != "sync_resolve" || !jsonBool(report, "ok") {
		t.Fatalf("unexpected sync resolve payload: %#v", report)
	}
}

func TestCLI_Contract_SyncPullReconcileConflictJSONError(t *testing.T) {
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
	_, _, err = runCLI([]string{"sync", "push"}, nil)
	if err != nil {
		t.Fatalf("initial sync push: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("local-v2"))
	if err != nil {
		t.Fatalf("secret set local-v2: %v", err)
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

	_, errOut, err := runCLI([]string{"sync", "pull", "--reconcile", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected sync pull --reconcile conflict")
	}
	assertExitCode(t, err, exitcode.CodeSyncConflict)
	errResp := parseJSONMap(t, errOut)
	requireJSONKeys(t, errResp, "ok", "error", "exit_code", "reason", "recommended_action")
	if errResp["reason"] != "overlapping_changes" || errResp["recommended_action"] != "manual_reconcile" {
		t.Fatalf("unexpected reconcile conflict error payload: %#v", errResp)
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

func preflightCheckNames(report map[string]any) []string {
	checks, _ := report["checks"].([]any)
	names := make([]string, 0, len(checks))
	for _, raw := range checks {
		check, _ := raw.(map[string]any)
		name, _ := check["name"].(string)
		if strings.TrimSpace(name) != "" {
			names = append(names, name)
		}
	}
	return names
}
