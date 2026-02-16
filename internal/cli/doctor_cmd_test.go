package cli

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"kimen/internal/exitcode"
)

func TestCLI_Doctor_JSON_OK(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	mapPath := filepath.Join(dir, "app.kmap")
	if err := os.WriteFile(mapPath, []byte("env API_KEY=api_key\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}

	out, errBuf, err := runCLI([]string{"doctor", "--map", mapPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("doctor --json: %v (stderr=%s)", err, errBuf)
	}

	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); !ok {
		t.Fatalf("expected ok=true, got %#v", report)
	}
}

func TestCLI_Doctor_FailsOnMissingVault(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "missing-vault.db")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	out, _, err := runCLI([]string{"doctor", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected doctor failure for missing vault")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeDoctorFailed {
		t.Fatalf("expected doctor exit code %d, got %d", exitcode.CodeDoctorFailed, ec.Code)
	}

	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); ok {
		t.Fatalf("expected ok=false, got %#v", report)
	}
	checks, _ := report["checks"].([]any)
	foundVaultError := false
	for _, c := range checks {
		check, _ := c.(map[string]any)
		if check["name"] == "vault_file" && check["status"] == doctorStatusError {
			foundVaultError = true
			break
		}
	}
	if !foundVaultError {
		t.Fatalf("expected vault_file error check, got %#v", report)
	}
}

func TestCLI_Doctor_StrictFailsOnWarnings(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	mapPath := filepath.Join(dir, "warn.kmap")
	if err := os.WriteFile(mapPath, []byte("file cfg.txt=api_key\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}

	out, _, err := runCLI([]string{"doctor", "--map", mapPath, "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict doctor failure on warnings")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeDoctorFailed {
		t.Fatalf("expected doctor exit code %d, got %d", exitcode.CodeDoctorFailed, ec.Code)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); ok {
		t.Fatalf("expected ok=false with --strict, got %#v", report)
	}
	if report["error_count"] != float64(0) {
		t.Fatalf("expected error_count=0 for warnings-only strict failure, got %#v", report)
	}
	if wc, _ := report["warning_count"].(float64); wc < 1 {
		t.Fatalf("expected warning_count>0, got %#v", report)
	}
}

func TestCLI_Doctor_AllowMissingVault(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "missing-vault.db")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	out, errBuf, err := runCLI([]string{"doctor", "--allow-missing-vault"}, nil)
	if err != nil {
		t.Fatalf("doctor --allow-missing-vault: %v (stderr=%s)", err, errBuf)
	}
	if !strings.Contains(out, "vault file not found") {
		t.Fatalf("expected missing vault warning in output, got %q", out)
	}
}

func TestCLI_Doctor_BundleIdentityPreflight_OK(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	idPath := filepath.Join(dir, "ci.agekey")
	bundlePath := filepath.Join(dir, "vault.age")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}

	out, errBuf, err := runCLI([]string{"bundle", "keygen", "--out", idPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("bundle keygen: %v (stderr=%s)", err, errBuf)
	}
	var keygen map[string]any
	if err := json.Unmarshal([]byte(out), &keygen); err != nil {
		t.Fatalf("keygen json parse: %v", err)
	}
	recipient, _ := keygen["recipient"].(string)
	if recipient == "" {
		t.Fatalf("missing recipient in keygen payload: %#v", keygen)
	}

	_, errBuf, err = runCLI([]string{"bundle", "seal", "--vault", vaultPath, "--out", bundlePath, "--recipient", recipient}, nil)
	if err != nil {
		t.Fatalf("bundle seal: %v (stderr=%s)", err, errBuf)
	}

	out, errBuf, err = runCLI([]string{"doctor", "--bundle-in", bundlePath, "--identity", idPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("doctor bundle preflight: %v (stderr=%s)", err, errBuf)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("doctor json parse: %v", err)
	}
	checks, _ := report["checks"].([]any)
	foundDecryptOK := false
	for _, c := range checks {
		check, _ := c.(map[string]any)
		if check["name"] == "bundle_decrypt" && check["status"] == doctorStatusOK {
			foundDecryptOK = true
			break
		}
	}
	if !foundDecryptOK {
		t.Fatalf("expected bundle_decrypt ok check, got %#v", report)
	}
}

func TestCLI_Doctor_BundleIdentityPreflight_InvalidIdentityFails(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	bundlePath := filepath.Join(dir, "vault.age")
	idPath := filepath.Join(dir, "bad.agekey")
	realID := filepath.Join(dir, "real.agekey")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}
	out, errBuf, err := runCLI([]string{"bundle", "keygen", "--out", realID, "--json"}, nil)
	if err != nil {
		t.Fatalf("bundle keygen: %v (stderr=%s)", err, errBuf)
	}
	var keygen map[string]any
	if err := json.Unmarshal([]byte(out), &keygen); err != nil {
		t.Fatalf("keygen json parse: %v", err)
	}
	recipient, _ := keygen["recipient"].(string)
	_, errBuf, err = runCLI([]string{"bundle", "seal", "--vault", vaultPath, "--out", bundlePath, "--recipient", recipient}, nil)
	if err != nil {
		t.Fatalf("bundle seal: %v (stderr=%s)", err, errBuf)
	}

	if err := os.WriteFile(idPath, []byte("not-an-age-identity\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, _, err = runCLI([]string{"doctor", "--bundle-in", bundlePath, "--identity", idPath, "--json"}, nil)
	if err == nil {
		t.Fatalf("expected doctor failure for invalid identity")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeDoctorFailed {
		t.Fatalf("expected doctor exit code %d, got %d", exitcode.CodeDoctorFailed, ec.Code)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("doctor json parse: %v", err)
	}
	checks, _ := report["checks"].([]any)
	foundIdentityError := false
	for _, c := range checks {
		check, _ := c.(map[string]any)
		if check["name"] == "bundle_identity" && check["status"] == doctorStatusError {
			foundIdentityError = true
			break
		}
	}
	if !foundIdentityError {
		t.Fatalf("expected bundle_identity error check, got %#v", report)
	}
}

func TestCLI_Doctor_StrictPassesWhenNoWarnings(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	mapPath := filepath.Join(dir, "ok.kmap")
	if err := os.WriteFile(mapPath, []byte("env API_KEY=api_key\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}

	out, errBuf, err := runCLI([]string{"doctor", "--map", mapPath, "--strict", "--json"}, nil)
	if err != nil {
		t.Fatalf("doctor strict should pass: %v (stderr=%s)", err, errBuf)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); !ok {
		t.Fatalf("expected ok=true, got %#v", report)
	}
	if report["warning_count"] != float64(0) {
		t.Fatalf("expected warning_count=0, got %#v", report)
	}
}
