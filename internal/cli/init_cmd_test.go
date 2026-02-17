package cli

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"kimen/internal/exitcode"
)

func TestCLI_InitCIPrSafety_JSONWritesWorkflow(t *testing.T) {
	dir := t.TempDir()
	outPath := filepath.Join(dir, ".github", "workflows", "kimen-pr-safety.yml")

	out, errBuf, err := runCLI([]string{
		"init", "ci-pr-safety",
		"--out", outPath,
		"--profile", "qa",
		"--command", "echo lint-check",
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("init ci-pr-safety --json: %v (stderr=%s)", err, errBuf)
	}
	resp := parseJSONMap(t, out)
	if resp["action"] != "init_ci_pr_safety" {
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
		"name: kimen-pr-safety",
		`default: "qa"`,
		`default: "echo lint-check"`,
		`./kimen map lint --profile "$PROFILE" --strict --json | tee kimen-map-lint.json`,
		`./kimen project plan --profile "$PROFILE" --json -- "${CMD[@]}" | tee kimen-plan.json`,
	}
	for _, snippet := range wantSnippets {
		if !strings.Contains(content, snippet) {
			t.Fatalf("expected workflow to contain %q\ncontent:\n%s", snippet, content)
		}
	}
}

func TestCLI_InitCIDeploy_JSONWritesWorkflow(t *testing.T) {
	dir := t.TempDir()
	outPath := filepath.Join(dir, ".github", "workflows", "kimen-deploy.yml")

	out, errBuf, err := runCLI([]string{
		"init", "ci-deploy",
		"--out", outPath,
		"--profile", "stage",
		"--deploy-command", "./scripts/release.sh --dry-run",
		"--json",
	}, nil)
	if err != nil {
		t.Fatalf("init ci-deploy --json: %v (stderr=%s)", err, errBuf)
	}
	resp := parseJSONMap(t, out)
	if resp["action"] != "init_ci_deploy" {
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
		"name: kimen-deploy",
		`default: "stage"`,
		`default: "./scripts/release.sh --dry-run"`,
		`--in vault.age \`,
		`./kimen project run --profile "${{ inputs.profile }}" -- "${DEPLOY_CMD[@]}"`,
	}
	for _, snippet := range wantSnippets {
		if !strings.Contains(content, snippet) {
			t.Fatalf("expected workflow to contain %q\ncontent:\n%s", snippet, content)
		}
	}
}

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
		`./kimen sync preflight \`,
		`--stale-threshold "$STALE_THRESHOLD" \`,
		`--json | tee kimen-sync-preflight.json`,
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
	if errResp["reason"] != "output_exists" {
		t.Fatalf("expected reason=output_exists, got %#v", errResp)
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
	if errResp["reason"] != "invalid_remote_type" {
		t.Fatalf("expected reason=invalid_remote_type, got %#v", errResp)
	}
}

func TestCLI_InitCIPrSafety_TemplateMatchesCheckedInWorkflow(t *testing.T) {
	workflowPath := filepath.Clean(filepath.Join("..", "..", ".github", "workflows", "kimen-pr-safety-template.yml"))
	b, err := os.ReadFile(workflowPath)
	if err != nil {
		t.Fatalf("read checked-in workflow template: %v", err)
	}

	want := normalizeNewlines(string(b))
	got := normalizeNewlines(renderDefaultCIPrSafetyTemplateWorkflow())
	if got != want {
		t.Fatalf("pr-safety template drift detected between canonical scaffold and %s", workflowPath)
	}
}

func TestCLI_InitCIDeploy_TemplateMatchesCheckedInWorkflow(t *testing.T) {
	workflowPath := filepath.Clean(filepath.Join("..", "..", ".github", "workflows", "kimen-deploy-template.yml"))
	b, err := os.ReadFile(workflowPath)
	if err != nil {
		t.Fatalf("read checked-in workflow template: %v", err)
	}

	want := normalizeNewlines(string(b))
	got := normalizeNewlines(renderDefaultCIDeployTemplateWorkflow())
	if got != want {
		t.Fatalf("deploy template drift detected between canonical scaffold and %s", workflowPath)
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
