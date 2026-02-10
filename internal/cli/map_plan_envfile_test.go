package cli

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCLI_Map_Profile_Plan_Envfile(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	mapDir := filepath.Join(dir, ".kimen", "profiles")
	mapPath := filepath.Join(mapDir, "linje-prod.kmap")

	if err := os.MkdirAll(mapDir, 0o700); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}
	if err := os.WriteFile(mapPath, []byte("env API_KEY=api_key\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	restore := withEnv(map[string]string{
		envVaultPath:        vaultPath,
		envPassphrase:       "pass",
		"KIMEN_PROFILE_DIR": mapDir,
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("shh123"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}

	// Plan via profile (json).
	out, errBuf, err := runCLI([]string{"plan", "--profile", "linje-prod", "--json", "--", "echo", "hi"}, nil)
	if err != nil {
		t.Fatalf("plan: %v (stderr=%s)", err, errBuf)
	}
	var p map[string]any
	if err := json.Unmarshal([]byte(out), &p); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if p["mode"] != "run" {
		t.Fatalf("unexpected mode: %#v", p["mode"])
	}

	// Envfile writes values to a file, not stdout.
	outPath := filepath.Join(dir, "app.env")
	_, errBuf, err = runCLI([]string{"envfile", "--profile", "linje-prod", "--out", outPath}, nil)
	if err != nil {
		t.Fatalf("envfile: %v (stderr=%s)", err, errBuf)
	}
	b, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatalf("ReadFile: %v", err)
	}
	if got := string(b); !strings.Contains(got, "API_KEY=shh123") {
		t.Fatalf("unexpected envfile contents: %q", got)
	}
	info, err := os.Stat(outPath)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("unexpected perms: %v", info.Mode().Perm())
	}

	// Dry-run does not print secret values.
	out, _, err = runCLI([]string{"run", "--profile", "linje-prod", "--dry-run", "--", "echo", "hi"}, nil)
	if err != nil {
		t.Fatalf("dry-run: %v", err)
	}
	if strings.Contains(out, "shh123") {
		t.Fatalf("dry-run output leaked secret value")
	}
}
