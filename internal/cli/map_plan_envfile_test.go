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
	if ok, _ := p["ok"].(bool); !ok {
		t.Fatalf("expected ok=true, got %#v", p)
	}
	if p["action"] != "plan" {
		t.Fatalf("expected action=plan, got %#v", p)
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

func TestCLI_ProjectPlan_JSON(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "app.kmap")
	content := strings.Join([]string{
		"env API_KEY=api_key",
		"file cfg.json=cfg_json",
	}, "\n") + "\n"
	if err := os.WriteFile(mapPath, []byte(content), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, errBuf, err := runCLI([]string{"project", "plan", "--map", mapPath, "--json", "--", "echo", "hello"}, nil)
	if err != nil {
		t.Fatalf("project plan --json: %v (stderr=%s)", err, errBuf)
	}
	var p map[string]any
	if err := json.Unmarshal([]byte(out), &p); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := p["ok"].(bool); !ok {
		t.Fatalf("expected ok=true, got %#v", p)
	}
	if p["action"] != "plan" {
		t.Fatalf("expected action=plan, got %#v", p)
	}
	if p["mode"] != "run" {
		t.Fatalf("unexpected mode: %#v", p["mode"])
	}
	cmd, _ := p["command"].([]any)
	if len(cmd) != 2 {
		t.Fatalf("unexpected command payload: %#v", p["command"])
	}

	out, errBuf, err = runCLI([]string{"project", "plan", "--mode", "render", "--map", mapPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("project plan --mode render: %v (stderr=%s)", err, errBuf)
	}
	if err := json.Unmarshal([]byte(out), &p); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if p["mode"] != "render" {
		t.Fatalf("unexpected render mode: %#v", p["mode"])
	}
}

func TestCLI_Plan_JSONErrorEnvelope(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "one.kmap")
	if err := os.WriteFile(mapPath, []byte("env A=a\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	_, errBuf, err := runCLI([]string{
		"plan",
		"--map", mapPath,
		"--against-map", mapPath,
		"--against-profile", "other",
		"--json",
	}, nil)
	if err == nil {
		t.Fatalf("expected plan failure")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodePlanFailed {
		t.Fatalf("expected plan exit code %d, got %d", exitcode.CodePlanFailed, ec.Code)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(errBuf), &payload); err != nil {
		t.Fatalf("error json parse: %v (stderr=%q)", err, errBuf)
	}
	if payload["exit_code"] != float64(exitcode.CodePlanFailed) {
		t.Fatalf("unexpected payload: %#v", payload)
	}
}

func TestCLI_Plan_InvalidProfileName(t *testing.T) {
	_, errBuf, err := runCLI([]string{
		"plan",
		"--profile", "../bad",
		"--json",
	}, nil)
	if err == nil {
		t.Fatalf("expected plan failure for invalid profile name")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodePlanFailed {
		t.Fatalf("expected plan exit code %d, got %d", exitcode.CodePlanFailed, ec.Code)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(errBuf), &payload); err != nil {
		t.Fatalf("error json parse: %v (stderr=%q)", err, errBuf)
	}
	if payload["exit_code"] != float64(exitcode.CodePlanFailed) {
		t.Fatalf("unexpected payload: %#v", payload)
	}
	errMsg, _ := payload["error"].(string)
	if !strings.Contains(errMsg, "invalid profile name") {
		t.Fatalf("expected invalid profile name message, got %#v", payload)
	}
}

func TestCLI_Plan_InvalidMode(t *testing.T) {
	_, errBuf, err := runCLI([]string{
		"plan",
		"--mode", "nope",
		"--json",
	}, nil)
	if err == nil {
		t.Fatalf("expected plan failure for invalid mode")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodePlanFailed {
		t.Fatalf("expected plan exit code %d, got %d", exitcode.CodePlanFailed, ec.Code)
	}
	var payload map[string]any
	if err := json.Unmarshal([]byte(errBuf), &payload); err != nil {
		t.Fatalf("error json parse: %v (stderr=%q)", err, errBuf)
	}
	if payload["exit_code"] != float64(exitcode.CodePlanFailed) {
		t.Fatalf("unexpected payload: %#v", payload)
	}
	errMsg, _ := payload["error"].(string)
	if !strings.Contains(errMsg, "invalid --mode") {
		t.Fatalf("expected invalid --mode message, got %#v", payload)
	}
}

func TestCLI_Envfile_JSONOutputAndErrorCodes(t *testing.T) {
	dir := t.TempDir()
	vaultPath := filepath.Join(dir, "vault.db")
	outPath := filepath.Join(dir, "app.env")

	restore := withEnv(map[string]string{
		envVaultPath:  vaultPath,
		envPassphrase: "pass",
	})
	defer restore()

	_, _, err := runCLI([]string{"vault", "init"}, nil)
	if err != nil {
		t.Fatalf("vault init: %v", err)
	}
	_, _, err = runCLI([]string{"secret", "set", "api_key", "--stdin"}, strings.NewReader("shh"))
	if err != nil {
		t.Fatalf("secret set: %v", err)
	}

	out, errBuf, err := runCLI([]string{"envfile", "--env", "API_KEY=api_key", "--out", outPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("envfile --json: %v (stderr=%s)", err, errBuf)
	}
	var okPayload map[string]any
	if err := json.Unmarshal([]byte(out), &okPayload); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if okPayload["action"] != "envfile" || okPayload["out"] != outPath {
		t.Fatalf("unexpected success payload: %#v", okPayload)
	}

	_, errBuf, err = runCLI([]string{"envfile", "--env", "API_KEY=missing", "--out", outPath, "--json"}, nil)
	if err == nil {
		t.Fatalf("expected envfile failure for missing secret")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeSecretNotFound {
		t.Fatalf("expected secret-not-found exit code %d, got %d", exitcode.CodeSecretNotFound, ec.Code)
	}
	var errPayload map[string]any
	if err := json.Unmarshal([]byte(errBuf), &errPayload); err != nil {
		t.Fatalf("error json parse: %v (stderr=%q)", err, errBuf)
	}
	if errPayload["exit_code"] != float64(exitcode.CodeSecretNotFound) {
		t.Fatalf("unexpected error payload: %#v", errPayload)
	}
}

func TestCLI_PlanDiff_JSON_AgainstMap(t *testing.T) {
	dir := t.TempDir()
	basePath := filepath.Join(dir, "base.kmap")
	curPath := filepath.Join(dir, "current.kmap")

	baseMap := strings.Join([]string{
		"env A=alpha",
		"env B=beta-old",
		"file old.txt=old-secret",
		"file keep.txt=keep-old",
		"envpath OLD_CFG=old.txt",
		"stdin stdin-old",
	}, "\n") + "\n"
	curMap := strings.Join([]string{
		"env B=beta-new",
		"env C=charlie",
		"file keep.txt=keep-new",
		"file new.txt=new-secret",
		"envpath NEW_CFG=new.txt",
		"stdin stdin-new",
	}, "\n") + "\n"

	if err := os.WriteFile(basePath, []byte(baseMap), 0o600); err != nil {
		t.Fatalf("WriteFile(base): %v", err)
	}
	if err := os.WriteFile(curPath, []byte(curMap), 0o600); err != nil {
		t.Fatalf("WriteFile(current): %v", err)
	}

	out, errBuf, err := runCLI([]string{"plan", "--map", curPath, "--against-map", basePath, "--json"}, nil)
	if err != nil {
		t.Fatalf("plan diff: %v (stderr=%s)", err, errBuf)
	}
	var p map[string]any
	if err := json.Unmarshal([]byte(out), &p); err != nil {
		t.Fatalf("json parse: %v", err)
	}

	if p["against"] != basePath {
		t.Fatalf("unexpected against label: %#v", p["against"])
	}
	diff, ok := p["diff"].(map[string]any)
	if !ok {
		t.Fatalf("missing diff object: %#v", p["diff"])
	}
	if diff["stdin_changed"] != true {
		t.Fatalf("expected stdin_changed=true: %#v", diff)
	}

	assertPlanDiffEntry(t, diff, "env_added", "var", "C")
	assertPlanDiffEntry(t, diff, "env_removed", "var", "A")
	assertPlanDiffEntry(t, diff, "env_changed", "var", "B")
	assertPlanDiffEntry(t, diff, "files_added", "relpath", "new.txt")
	assertPlanDiffEntry(t, diff, "files_removed", "relpath", "old.txt")
	assertPlanDiffEntry(t, diff, "files_changed", "relpath", "keep.txt")
	assertPlanDiffEntry(t, diff, "envpaths_added", "var", "NEW_CFG")
	assertPlanDiffEntry(t, diff, "envpaths_removed", "var", "OLD_CFG")
}

func TestCLI_PlanDiff_Human_AgainstProfile(t *testing.T) {
	dir := t.TempDir()
	profileDir := filepath.Join(dir, "profiles")
	if err := os.MkdirAll(profileDir, 0o700); err != nil {
		t.Fatalf("MkdirAll: %v", err)
	}

	basePath := filepath.Join(profileDir, "base.kmap")
	curPath := filepath.Join(profileDir, "current.kmap")
	if err := os.WriteFile(basePath, []byte("env API_KEY=old\n"), 0o600); err != nil {
		t.Fatalf("WriteFile(base): %v", err)
	}
	if err := os.WriteFile(curPath, []byte("env API_KEY=new\n"), 0o600); err != nil {
		t.Fatalf("WriteFile(current): %v", err)
	}

	restore := withEnv(map[string]string{
		"KIMEN_PROFILE_DIR": profileDir,
	})
	defer restore()

	out, errBuf, err := runCLI([]string{"plan", "--profile", "current", "--against-profile", "base"}, nil)
	if err != nil {
		t.Fatalf("plan diff profile: %v (stderr=%s)", err, errBuf)
	}
	if !strings.Contains(out, "diff:") {
		t.Fatalf("expected diff section in output: %q", out)
	}
	if !strings.Contains(out, "against: profile:base") {
		t.Fatalf("expected against profile label in output: %q", out)
	}
	if !strings.Contains(out, "env changed:") {
		t.Fatalf("expected env changed section in output: %q", out)
	}
}

func assertPlanDiffEntry(t *testing.T, diff map[string]any, field, keyName, want string) {
	t.Helper()
	raw, ok := diff[field]
	if !ok {
		t.Fatalf("missing diff field %q in %#v", field, diff)
	}
	items, ok := raw.([]any)
	if !ok {
		t.Fatalf("diff field %q has unexpected type: %#v", field, raw)
	}
	for _, item := range items {
		m, ok := item.(map[string]any)
		if !ok {
			continue
		}
		v := m[keyName]
		if v == nil {
			switch keyName {
			case "var":
				v = m["Var"]
			case "relpath":
				v = m["RelPath"]
			case "from":
				v = m["From"]
			case "to":
				v = m["To"]
			}
		}
		if v == want {
			return
		}
	}
	t.Fatalf("missing %s=%q in %s: %#v", keyName, want, field, raw)
}
