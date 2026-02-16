package cli

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"kimen/internal/exitcode"
)

func TestCLI_InitCISyncGate_JSONWritesWorkflow(t *testing.T) {
	dir := t.TempDir()
	outPath := filepath.Join(dir, ".github", "workflows", "kimen-sync-gate.yml")

	out, errBuf, err := runCLI([]string{
		"init", "ci-sync-gate",
		"--out", outPath,
		"--remote-name", "team-prod",
		"--remote-type", "git",
		"--remote-path", "git@github.com:acme/secrets.git",
		"--remote-branch", "main",
		"--remote-bundle-path", "infra/vault.age",
		"--local-bundle", "secrets/vault.age",
		"--profile", "prod",
		"--stale-threshold", "15m",
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("init ci-sync-gate --json: %v (stderr=%s)", err, errBuf)
	}
	resp := parseJSONMap(t, out)
	if resp["action"] != "init_ci_sync_gate" {
		t.Fatalf("unexpected init action: %#v", resp)
	}
	if resp["out"] != outPath {
		t.Fatalf("unexpected output path in init response: %#v", resp)
	}

	b, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatalf("read scaffolded workflow: %v", err)
	}
	content := string(b)
	wantSnippets := []string{
		"name: kimen-sync-gate",
		`default: "team-prod"`,
		`default: "git"`,
		`default: "git@github.com:acme/secrets.git"`,
		`default: "infra/vault.age"`,
		`default: "secrets/vault.age"`,
		`default: "prod"`,
		`default: "15m"`,
		`kimen sync status --remote "$REMOTE_NAME" --stale-threshold "$STALE_THRESHOLD" --strict --json`,
		`kimen sync push --remote "$REMOTE_NAME" --dry-run --json`,
	}
	for _, snippet := range wantSnippets {
		if !strings.Contains(content, snippet) {
			t.Fatalf("expected workflow to contain %q\ncontent:\n%s", snippet, content)
		}
	}
}

func TestCLI_InitCISyncGate_RefusesOverwriteWithoutForce(t *testing.T) {
	dir := t.TempDir()
	outPath := filepath.Join(dir, "kimen-sync-gate.yml")
	if err := os.WriteFile(outPath, []byte("existing\n"), 0o644); err != nil {
		t.Fatalf("seed output file: %v", err)
	}

	_, errOut, err := runCLI([]string{
		"init", "ci-sync-gate",
		"--out", outPath,
		"--json",
	}, nil)
	if err == nil {
		t.Fatalf("expected init to fail when output already exists")
	}
	assertExitCode(t, err, exitcode.CodeInitFailed)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeInitFailed) {
		t.Fatalf("unexpected init error payload: %#v", errResp)
	}
}

func TestCLI_InitCISyncGate_ForceOverwrite(t *testing.T) {
	dir := t.TempDir()
	outPath := filepath.Join(dir, "kimen-sync-gate.yml")
	if err := os.WriteFile(outPath, []byte("old-content\n"), 0o644); err != nil {
		t.Fatalf("seed output file: %v", err)
	}

	_, errBuf, err := runCLI([]string{
		"init", "ci-sync-gate",
		"--out", outPath,
		"--force",
	}, nil)
	if err != nil {
		t.Fatalf("init ci-sync-gate --force: %v (stderr=%s)", err, errBuf)
	}

	b, err := os.ReadFile(outPath)
	if err != nil {
		t.Fatalf("read output file: %v", err)
	}
	content := string(b)
	if strings.Contains(content, "old-content") {
		t.Fatalf("expected output file to be overwritten, still found old marker")
	}
	if !strings.Contains(content, "name: kimen-sync-gate") {
		t.Fatalf("unexpected scaffold output: %s", content)
	}
}

func TestCLI_InitCISyncGate_InvalidRemoteType(t *testing.T) {
	dir := t.TempDir()
	outPath := filepath.Join(dir, "kimen-sync-gate.yml")

	_, errOut, err := runCLI([]string{
		"init", "ci-sync-gate",
		"--out", outPath,
		"--remote-type", "http",
		"--json",
	}, nil)
	if err == nil {
		t.Fatalf("expected invalid remote type to fail")
	}
	assertExitCode(t, err, exitcode.CodeInitFailed)
	errResp := parseJSONMap(t, errOut)
	if errResp["exit_code"] != float64(exitcode.CodeInitFailed) {
		t.Fatalf("unexpected init error payload: %#v", errResp)
	}
}

func TestCLI_InitCISyncGate_TemplateMatchesCheckedInWorkflow(t *testing.T) {
	workflowPath := filepath.Clean(filepath.Join("..", "..", ".github", "workflows", "kimen-sync-gate-template.yml"))
	b, err := os.ReadFile(workflowPath)
	if err != nil {
		t.Fatalf("read checked-in workflow template: %v", err)
	}

	want := normalizeNewlines(string(b))
	got := normalizeNewlines(renderDefaultCISyncGateTemplateWorkflow())
	if got != want {
		t.Fatalf("sync-gate template drift detected between canonical scaffold and %s", workflowPath)
	}
}

func normalizeNewlines(s string) string {
	return strings.ReplaceAll(s, "\r\n", "\n")
}
