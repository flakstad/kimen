package cli

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

func TestCLI_VaultInit_SecretSet_List_Render(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	outDir := filepath.Join(dir, "out")

	restore := withEnv(map[string]string{
		envVaultPath:   vaultPath,
		envPassphrase:  "pass",
		"KIMEN_UNUSED": "1",
	})
	defer restore()

	out, errBuf, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v (stderr=%s)", err, errBuf)
	}
	_ = out

	_, errBuf, err = runCLI([]string{"secret", "set", "foo", "--stdin"}, strings.NewReader("mysecret"))
	if err != nil {
		t.Fatalf("secret set: %v (stderr=%s)", err, errBuf)
	}

	out, errBuf, err = runCLI([]string{"secret", "list", "--json"}, nil)
	if err != nil {
		t.Fatalf("secret list: %v (stderr=%s)", err, errBuf)
	}
	if !strings.Contains(out, "foo") {
		t.Fatalf("expected foo in output, got %q", out)
	}

	_, errBuf, err = runCLI([]string{"render", "--dir", outDir, "--file", "config.txt=foo"}, nil)
	if err != nil {
		t.Fatalf("render: %v (stderr=%s)", err, errBuf)
	}
	b, err := os.ReadFile(filepath.Join(outDir, "config.txt"))
	if err != nil {
		t.Fatalf("read rendered file: %v", err)
	}
	if string(b) != "mysecret" {
		t.Fatalf("unexpected rendered content: %q", string(b))
	}

	_, errBuf, err = runCLI([]string{"project", "render", "--dir", outDir, "--file", "config2.txt=foo"}, nil)
	if err != nil {
		t.Fatalf("project render: %v (stderr=%s)", err, errBuf)
	}
	b, err = os.ReadFile(filepath.Join(outDir, "config2.txt"))
	if err != nil {
		t.Fatalf("read rendered file: %v", err)
	}
	if string(b) != "mysecret" {
		t.Fatalf("unexpected rendered content: %q", string(b))
	}
}

func TestCLI_SecretGetIsUnsafeByDefault(t *testing.T) {
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

	_, _, err = runCLI([]string{"secret", "set", "x", "--stdin"}, strings.NewReader("v"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}

	_, _, err = runCLI([]string{"secret", "get", "x"}, nil)
	if err == nil {
		t.Fatalf("expected error")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != 1 {
		t.Fatalf("expected generic exit code 1, got %d", ec.Code)
	}
}

func TestCLI_SecretMoveAndRemove(t *testing.T) {
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
	_, _, err = runCLI([]string{"secret", "set", "old_name", "--stdin"}, strings.NewReader("value"))
	if err != nil {
		t.Fatalf("secret set old_name: %v", err)
	}

	_, _, err = runCLI([]string{"secret", "mv", "old_name", "new_name"}, nil)
	if err != nil {
		t.Fatalf("secret mv: %v", err)
	}

	out, _, err := runCLI([]string{"secret", "list", "--json"}, nil)
	if err != nil {
		t.Fatalf("secret list: %v", err)
	}
	if strings.Contains(out, "old_name") {
		t.Fatalf("old_name should not be listed after rename: %q", out)
	}
	if !strings.Contains(out, "new_name") {
		t.Fatalf("new_name should be listed after rename: %q", out)
	}

	out, _, err = runCLI([]string{"secret", "get", "new_name", "--unsafe-stdout"}, nil)
	if err != nil {
		t.Fatalf("secret get new_name: %v", err)
	}
	if out != "value" {
		t.Fatalf("unexpected secret value after rename: %q", out)
	}

	_, _, err = runCLI([]string{"secret", "rm", "new_name"}, nil)
	if err != nil {
		t.Fatalf("secret rm: %v", err)
	}

	_, _, err = runCLI([]string{"secret", "get", "new_name", "--unsafe-stdout"}, nil)
	if !errors.Is(err, vault.ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound after remove, got %v", err)
	}
}

func TestCLI_SecretMoveAndRemove_Errors(t *testing.T) {
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
	_, _, err = runCLI([]string{"secret", "set", "a", "--stdin"}, strings.NewReader("va"))
	if err != nil {
		t.Fatalf("secret set a: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "b", "--stdin"}, strings.NewReader("vb"))
	if err != nil {
		t.Fatalf("secret set b: %v", err)
	}

	_, _, err = runCLI([]string{"secret", "mv", "a", "b"}, nil)
	if !errors.Is(err, vault.ErrSecretExists) {
		t.Fatalf("expected ErrSecretExists, got %v", err)
	}
	assertExitCode(t, err, exitcode.CodeSecretExists)

	_, _, err = runCLI([]string{"secret", "mv", "missing", "c"}, nil)
	if !errors.Is(err, vault.ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound, got %v", err)
	}
	assertExitCode(t, err, exitcode.CodeSecretNotFound)

	_, _, err = runCLI([]string{"secret", "rm", "missing"}, nil)
	if !errors.Is(err, vault.ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound, got %v", err)
	}
	assertExitCode(t, err, exitcode.CodeSecretNotFound)

	_, _, err = runCLI([]string{"secret", "mv", "a", "a"}, nil)
	if err == nil {
		t.Fatalf("expected error when renaming to the same name")
	}
	assertExitCode(t, err, 1)
}

func TestCLI_SecretJSONOutput(t *testing.T) {
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

	out, errBuf, err := runCLI([]string{"secret", "set", "json_key", "--stdin", "--json"}, strings.NewReader("json-val"))
	if err != nil {
		t.Fatalf("secret set --json: %v (stderr=%s)", err, errBuf)
	}
	var setResp map[string]any
	if err := json.Unmarshal([]byte(out), &setResp); err != nil {
		t.Fatalf("set json parse: %v", err)
	}
	if setResp["action"] != "set" || setResp["name"] != "json_key" {
		t.Fatalf("unexpected set response: %#v", setResp)
	}
	if setResp["exit_code"] != float64(0) {
		t.Fatalf("expected secret set exit_code=0, got %#v", setResp)
	}

	out, errBuf, err = runCLI([]string{"secret", "list", "--json"}, nil)
	if err != nil {
		t.Fatalf("secret list --json: %v (stderr=%s)", err, errBuf)
	}
	var listResp map[string]any
	if err := json.Unmarshal([]byte(out), &listResp); err != nil {
		t.Fatalf("list json parse: %v", err)
	}
	if listResp["action"] != "list" {
		t.Fatalf("unexpected list response: %#v", listResp)
	}

	out, errBuf, err = runCLI([]string{"secret", "get", "json_key", "--unsafe-stdout", "--json"}, nil)
	if err != nil {
		t.Fatalf("secret get --json: %v (stderr=%s)", err, errBuf)
	}
	var getResp map[string]any
	if err := json.Unmarshal([]byte(out), &getResp); err != nil {
		t.Fatalf("get json parse: %v", err)
	}
	if getResp["action"] != "get" || getResp["encoding"] != "base64" {
		t.Fatalf("unexpected get response: %#v", getResp)
	}

	out, errBuf, err = runCLI([]string{"secret", "rm", "json_key", "--json"}, nil)
	if err != nil {
		t.Fatalf("secret rm --json: %v (stderr=%s)", err, errBuf)
	}
	var rmResp map[string]any
	if err := json.Unmarshal([]byte(out), &rmResp); err != nil {
		t.Fatalf("rm json parse: %v", err)
	}
	if rmResp["action"] != "rm" {
		t.Fatalf("unexpected rm response: %#v", rmResp)
	}

	_, errOut, err := runCLI([]string{"secret", "rm", "missing", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected rm missing failure")
	}
	var errResp map[string]any
	if err := json.Unmarshal([]byte(errOut), &errResp); err != nil {
		t.Fatalf("error json parse: %v", err)
	}
	if errResp["exit_code"] != float64(exitcode.CodeSecretNotFound) {
		t.Fatalf("unexpected error response: %#v", errResp)
	}
	if errResp["reason"] != "secret_not_found" {
		t.Fatalf("expected reason=secret_not_found, got %#v", errResp)
	}
}

func assertExitCode(t *testing.T, err error, code int) {
	t.Helper()
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T (%v)", err, err)
	}
	if ec.Code != code {
		t.Fatalf("expected exit code %d, got %d", code, ec.Code)
	}
}

func TestCLI_RunAndRender_TypedProjectionExitCodes(t *testing.T) {
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

	_, _, err = runCLI([]string{"run", "--env", "API_KEY=missing", "--", "echo", "hello"}, nil)
	if !errors.Is(err, vault.ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound for run, got %v", err)
	}
	assertExitCode(t, err, exitcode.CodeSecretNotFound)

	_, _, err = runCLI([]string{"render", "--dir", filepath.Join(dir, "out"), "--file", "cfg.txt=missing"}, nil)
	if !errors.Is(err, vault.ErrSecretNotFound) {
		t.Fatalf("expected ErrSecretNotFound for render, got %v", err)
	}
	assertExitCode(t, err, exitcode.CodeSecretNotFound)

	_, _, err = runCLI([]string{"render", "--file", "cfg.txt=missing"}, nil)
	if err == nil {
		t.Fatalf("expected render failure when --dir is missing")
	}
	assertExitCode(t, err, exitcode.CodeProjectionFailed)
}

func TestCLI_RunAndRender_JSONProjectionEnvelopes(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	renderDir := filepath.Join(dir, "render-out")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
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

	out, errBuf, err := runCLI([]string{"render", "--dir", renderDir, "--file", "cfg.txt=api_key", "--json"}, nil)
	if err != nil {
		t.Fatalf("render --json: %v (stderr=%s)", err, errBuf)
	}
	var renderResp map[string]any
	if err := json.Unmarshal([]byte(out), &renderResp); err != nil {
		t.Fatalf("render json parse: %v", err)
	}
	if renderResp["action"] != "render" {
		t.Fatalf("unexpected render response: %#v", renderResp)
	}
	if renderResp["exit_code"] != float64(0) {
		t.Fatalf("expected render exit_code=0, got %#v", renderResp)
	}

	_, errOut, err := runCLI([]string{"run", "--env", "API_KEY=missing", "--json", "--", "echo", "hello"}, nil)
	if err == nil {
		t.Fatalf("expected run failure")
	}
	assertExitCode(t, err, exitcode.CodeSecretNotFound)
	var runErr map[string]any
	if err := json.Unmarshal([]byte(errOut), &runErr); err != nil {
		t.Fatalf("run error json parse: %v (stderr=%q)", err, errOut)
	}
	if runErr["exit_code"] != float64(exitcode.CodeSecretNotFound) {
		t.Fatalf("unexpected run error response: %#v", runErr)
	}
	if runErr["reason"] != "secret_not_found" {
		t.Fatalf("expected run reason=secret_not_found, got %#v", runErr)
	}

	_, renderErrOut, err := runCLI([]string{"render", "--file", "cfg.txt=api_key", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected render failure without --dir")
	}
	assertExitCode(t, err, exitcode.CodeProjectionFailed)
	var renderErr map[string]any
	if err := json.Unmarshal([]byte(renderErrOut), &renderErr); err != nil {
		t.Fatalf("render error json parse: %v (stderr=%q)", err, renderErrOut)
	}
	if renderErr["reason"] != "missing_render_target" {
		t.Fatalf("expected render reason=missing_render_target, got %#v", renderErr)
	}
}

func TestCLI_Render_SystemdServiceMode(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	runtimeBase := filepath.Join(dir, "run")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("systemd-secret"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}

	out, errBuf, err := runCLI([]string{
		"render",
		"--systemd-service", "linje-api",
		"--runtime-dir", runtimeBase,
		"--print-systemd-hints",
		"--file", "cfg.txt=api_key",
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("systemd render: %v (stderr=%s)", err, errBuf)
	}

	var resp map[string]any
	if err := json.Unmarshal([]byte(out), &resp); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	wantOut := filepath.Join(runtimeBase, "kimen", "linje-api")
	if resp["out_dir"] != wantOut {
		t.Fatalf("unexpected out_dir: got=%#v want=%q", resp["out_dir"], wantOut)
	}
	hints, _ := resp["hints"].([]any)
	if len(hints) == 0 {
		t.Fatalf("expected systemd hints in response: %#v", resp)
	}

	b, err := os.ReadFile(filepath.Join(wantOut, "cfg.txt"))
	if err != nil {
		t.Fatalf("read rendered file: %v", err)
	}
	if string(b) != "systemd-secret" {
		t.Fatalf("unexpected rendered value: %q", string(b))
	}

	_, _, err = runCLI([]string{
		"render",
		"--dir", filepath.Join(dir, "out"),
		"--systemd-service", "linje-api",
		"--file", "cfg.txt=api_key",
	}, nil)
	if err == nil {
		t.Fatalf("expected error when both --dir and --systemd-service are set")
	}
	assertExitCode(t, err, exitcode.CodeProjectionFailed)
}

func TestCLI_ConfigUnlockExecIsUsed(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")

	restore := withEnv(map[string]string{
		envVaultPath:          vaultPath,
		envConfigPath:         configPath,
		"GO_WANT_HELPER_PROC": "1",
		"KIMEN_HELPER_PP":     "pass",
	})
	defer restore()

	// Configure unlock to call back into the test binary.
	_, errBuf, err := runCLI([]string{
		"config", "unlock", "set", "exec", "--",
		os.Args[0], "-test.run=TestHelperPassphraseCommand", "--",
	}, nil)
	if err != nil {
		t.Fatalf("config unlock set: %v (stderr=%s)", err, errBuf)
	}

	// With no KIMEN_PASSPHRASE or flags, vault init should use the configured exec.
	_, errBuf, err = runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v (stderr=%s)", err, errBuf)
	}
}

func TestCLI_VaultJSONAndTypedErrors(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, errOut, err := runCLI([]string{"vault", "info", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected vault info failure for missing vault")
	}
	assertExitCode(t, err, exitcode.CodeVaultNotFound)
	var missingResp map[string]any
	if err := json.Unmarshal([]byte(errOut), &missingResp); err != nil {
		t.Fatalf("vault info error json parse: %v (stderr=%q)", err, errOut)
	}
	if missingResp["exit_code"] != float64(exitcode.CodeVaultNotFound) {
		t.Fatalf("unexpected vault info error response: %#v", missingResp)
	}
	if missingResp["reason"] != "vault_not_found" {
		t.Fatalf("expected reason=vault_not_found, got %#v", missingResp)
	}

	out, errBuf, err := runCLI([]string{"vault", "init", "--json"}, nil)
	if err != nil {
		t.Fatalf("vault init --json: %v (stderr=%s)", err, errBuf)
	}
	var initResp map[string]any
	if err := json.Unmarshal([]byte(out), &initResp); err != nil {
		t.Fatalf("vault init json parse: %v", err)
	}
	if initResp["action"] != "vault_init" {
		t.Fatalf("unexpected vault init response: %#v", initResp)
	}
	if initResp["exit_code"] != float64(0) {
		t.Fatalf("expected vault init exit_code=0, got %#v", initResp)
	}

	out, errBuf, err = runCLI([]string{"vault", "info", "--json"}, nil)
	if err != nil {
		t.Fatalf("vault info --json: %v (stderr=%s)", err, errBuf)
	}
	var infoResp map[string]any
	if err := json.Unmarshal([]byte(out), &infoResp); err != nil {
		t.Fatalf("vault info json parse: %v", err)
	}
	if infoResp["action"] != "vault_info" || infoResp["path"] != vaultPath {
		t.Fatalf("unexpected vault info response: %#v", infoResp)
	}
}

func TestCLI_BundleJSONAndTypedErrors(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	identityPath := filepath.Join(dir, "bundle.agekey")
	bundlePath := filepath.Join(dir, "vault.age")
	outVault := filepath.Join(dir, "vault.out.db")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}

	out, errBuf, err := runCLI([]string{"bundle", "keygen", "--out", identityPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("bundle keygen --json: %v (stderr=%s)", err, errBuf)
	}
	var keygenResp map[string]any
	if err := json.Unmarshal([]byte(out), &keygenResp); err != nil {
		t.Fatalf("bundle keygen json parse: %v", err)
	}
	if keygenResp["action"] != "bundle_keygen" {
		t.Fatalf("unexpected bundle keygen response: %#v", keygenResp)
	}
	if keygenResp["exit_code"] != float64(0) {
		t.Fatalf("expected bundle keygen exit_code=0, got %#v", keygenResp)
	}
	recipient, _ := keygenResp["recipient"].(string)
	if recipient == "" {
		t.Fatalf("expected recipient in keygen response: %#v", keygenResp)
	}

	out, errBuf, err = runCLI([]string{
		"bundle", "seal",
		"--vault", vaultPath,
		"--out", bundlePath,
		"--recipient", recipient,
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("bundle seal --json: %v (stderr=%s)", err, errBuf)
	}
	var sealResp map[string]any
	if err := json.Unmarshal([]byte(out), &sealResp); err != nil {
		t.Fatalf("bundle seal json parse: %v", err)
	}
	if sealResp["action"] != "bundle_seal" {
		t.Fatalf("unexpected bundle seal response: %#v", sealResp)
	}

	out, errBuf, err = runCLI([]string{
		"bundle", "open",
		"--in", bundlePath,
		"--out-vault", outVault,
		"--identity", identityPath,
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("bundle open --json: %v (stderr=%s)", err, errBuf)
	}
	var openResp map[string]any
	if err := json.Unmarshal([]byte(out), &openResp); err != nil {
		t.Fatalf("bundle open json parse: %v", err)
	}
	if openResp["action"] != "bundle_open" || openResp["out_vault"] != outVault {
		t.Fatalf("unexpected bundle open response: %#v", openResp)
	}

	_, errOut, err := runCLI([]string{
		"bundle", "open",
		"--in", filepath.Join(dir, "missing.age"),
		"--out-vault", filepath.Join(dir, "missing.out.db"),
		"--identity", identityPath,
		"--json",
	}, nil)
	if err == nil {
		t.Fatalf("expected bundle open failure")
	}
	assertExitCode(t, err, exitcode.CodeBundleFailed)
	var errResp map[string]any
	if err := json.Unmarshal([]byte(errOut), &errResp); err != nil {
		t.Fatalf("bundle open error json parse: %v (stderr=%q)", err, errOut)
	}
	if errResp["exit_code"] != float64(exitcode.CodeBundleFailed) {
		t.Fatalf("unexpected bundle open error response: %#v", errResp)
	}
	if errResp["reason"] != "input_missing" {
		t.Fatalf("expected reason=input_missing, got %#v", errResp)
	}
}

func TestCLI_ConfigJSONAndTypedErrors(t *testing.T) {
	dir := t.TempDir()
	configPath := filepath.Join(dir, "config.json")

	restore := withEnv(map[string]string{
		envConfigPath: configPath,
	})
	defer restore()

	out, errBuf, err := runCLI([]string{"config", "path", "--json"}, nil)
	if err != nil {
		t.Fatalf("config path --json: %v (stderr=%s)", err, errBuf)
	}
	var pathResp map[string]any
	if err := json.Unmarshal([]byte(out), &pathResp); err != nil {
		t.Fatalf("config path json parse: %v", err)
	}
	if pathResp["action"] != "config_path" {
		t.Fatalf("unexpected config path response: %#v", pathResp)
	}
	if pathResp["exit_code"] != float64(0) {
		t.Fatalf("expected config path exit_code=0, got %#v", pathResp)
	}

	out, errBuf, err = runCLI([]string{"config", "unlock", "set", "env", "--json"}, nil)
	if err != nil {
		t.Fatalf("config unlock set --json: %v (stderr=%s)", err, errBuf)
	}
	var setResp map[string]any
	if err := json.Unmarshal([]byte(out), &setResp); err != nil {
		t.Fatalf("config unlock set json parse: %v", err)
	}
	if setResp["action"] != "config_unlock_set" || setResp["method"] != "env" {
		t.Fatalf("unexpected config unlock set response: %#v", setResp)
	}

	out, errBuf, err = runCLI([]string{"config", "unlock", "show", "--json"}, nil)
	if err != nil {
		t.Fatalf("config unlock show --json: %v (stderr=%s)", err, errBuf)
	}
	var showResp map[string]any
	if err := json.Unmarshal([]byte(out), &showResp); err != nil {
		t.Fatalf("config unlock show json parse: %v", err)
	}
	if showResp["action"] != "config_unlock_show" || showResp["method"] != "env" {
		t.Fatalf("unexpected config unlock show response: %#v", showResp)
	}

	out, errBuf, err = runCLI([]string{"config", "unlock", "clear", "--json"}, nil)
	if err != nil {
		t.Fatalf("config unlock clear --json: %v (stderr=%s)", err, errBuf)
	}
	var clearResp map[string]any
	if err := json.Unmarshal([]byte(out), &clearResp); err != nil {
		t.Fatalf("config unlock clear json parse: %v", err)
	}
	if clearResp["action"] != "config_unlock_clear" || clearResp["method"] != "prompt" {
		t.Fatalf("unexpected config unlock clear response: %#v", clearResp)
	}

	_, errOut, err := runCLI([]string{"config", "unlock", "set", "bogus", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected invalid unlock method failure")
	}
	assertExitCode(t, err, exitcode.CodeConfigFailed)
	var errResp map[string]any
	if err := json.Unmarshal([]byte(errOut), &errResp); err != nil {
		t.Fatalf("config unlock set error json parse: %v (stderr=%q)", err, errOut)
	}
	if errResp["exit_code"] != float64(exitcode.CodeConfigFailed) {
		t.Fatalf("unexpected config error response: %#v", errResp)
	}
	if errResp["reason"] != "unknown_unlock_method" {
		t.Fatalf("expected reason=unknown_unlock_method, got %#v", errResp)
	}
}

func TestCLI_RemoteJSONAndTypedErrors(t *testing.T) {
	_, errOut, err := runCLI([]string{"remote", "add", "bad/name", "--path", "/tmp/remote", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected remote add invalid-name failure")
	}
	assertExitCode(t, err, exitcode.CodeRemoteFailed)
	var errResp map[string]any
	if err := json.Unmarshal([]byte(errOut), &errResp); err != nil {
		t.Fatalf("remote add error json parse: %v (stderr=%q)", err, errOut)
	}
	if errResp["exit_code"] != float64(exitcode.CodeRemoteFailed) {
		t.Fatalf("unexpected remote error response: %#v", errResp)
	}
	if errResp["reason"] != "invalid_remote_name" {
		t.Fatalf("expected reason=invalid_remote_name, got %#v", errResp)
	}
}

func TestCLI_HumanModeErrors_WriteStderr(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	configPath := filepath.Join(dir, "config.json")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envConfigPath: configPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, errBuf, err := runCLI([]string{"vault", "info"}, nil)
	if err == nil {
		t.Fatalf("expected vault info failure before init")
	}
	assertExitCode(t, err, exitcode.CodeVaultNotFound)
	if !strings.Contains(errBuf, "vault not found") {
		t.Fatalf("expected human error on stderr for vault info, got %q", errBuf)
	}

	_, errBuf, err = runCLI([]string{"bundle", "open"}, nil)
	if err == nil {
		t.Fatalf("expected bundle open usage failure")
	}
	assertExitCode(t, err, exitcode.CodeBundleFailed)
	if !strings.Contains(errBuf, "--in is required") {
		t.Fatalf("expected human error on stderr for bundle open, got %q", errBuf)
	}

	_, errBuf, err = runCLI([]string{"config", "unlock", "set", "bogus"}, nil)
	if err == nil {
		t.Fatalf("expected config unlock set failure")
	}
	assertExitCode(t, err, exitcode.CodeConfigFailed)
	if !strings.Contains(errBuf, "unknown unlock method") {
		t.Fatalf("expected human error on stderr for config unlock set, got %q", errBuf)
	}

	_, errBuf, err = runCLI([]string{"render", "--file", "cfg.txt=missing"}, nil)
	if err == nil {
		t.Fatalf("expected render failure without --dir")
	}
	assertExitCode(t, err, exitcode.CodeProjectionFailed)
	if !strings.Contains(errBuf, "--dir is required") {
		t.Fatalf("expected human error on stderr for render, got %q", errBuf)
	}
}

func TestHelperPassphraseCommand(t *testing.T) {
	if os.Getenv("GO_WANT_HELPER_PROC") != "1" {
		return
	}
	fmt.Fprintln(os.Stdout, os.Getenv("KIMEN_HELPER_PP"))
	os.Exit(0)
}

func TestCLI_Version(t *testing.T) {
	out, errBuf, err := runCLI([]string{"version"}, nil)
	if err != nil {
		t.Fatalf("version: %v (stderr=%s)", err, errBuf)
	}
	if strings.TrimSpace(out) == "" {
		t.Fatalf("expected non-empty version output")
	}

	out, errBuf, err = runCLI([]string{"version", "--json"}, nil)
	if err != nil {
		t.Fatalf("version --json: %v (stderr=%s)", err, errBuf)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(out), &payload); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := payload["ok"].(bool); !ok {
		t.Fatalf("expected ok=true in version payload, got %#v", payload)
	}
	if payload["action"] != "version" {
		t.Fatalf("expected action=version, got %#v", payload)
	}
	if payload["exit_code"] != float64(0) {
		t.Fatalf("expected exit_code=0 in version payload, got %#v", payload)
	}
	if payload["version"] == "" || payload["raw_version"] == "" {
		t.Fatalf("unexpected version payload: %#v", payload)
	}
}

func withEnv(kv map[string]string) func() {
	orig := make(map[string]*string, len(kv))
	for k, v := range kv {
		if old, ok := os.LookupEnv(k); ok {
			cp := old
			orig[k] = &cp
		} else {
			orig[k] = nil
		}
		_ = os.Setenv(k, v)
	}
	return func() {
		for k, v := range orig {
			if v == nil {
				_ = os.Unsetenv(k)
			} else {
				_ = os.Setenv(k, *v)
			}
		}
	}
}

func runCLI(args []string, stdin *strings.Reader) (string, string, error) {
	root := NewRootCommand()
	var out bytes.Buffer
	var errBuf bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&errBuf)
	if stdin != nil {
		root.SetIn(stdin)
	}
	root.SetArgs(args)
	err := root.Execute()
	return out.String(), errBuf.String(), err
}
