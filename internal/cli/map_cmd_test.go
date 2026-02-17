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

func TestCLI_MapLint_OK(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "ok.kmap")
	if err := os.WriteFile(mapPath, []byte("env API_KEY=api_key\nfile key.json=gcp_key\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, errBuf, err := runCLI([]string{"map", "lint", "--map", mapPath}, nil)
	if err != nil {
		t.Fatalf("map lint: %v (stderr=%s)", err, errBuf)
	}
	if !strings.Contains(out, "ok (") {
		t.Fatalf("expected ok output, got %q", out)
	}

	out, errBuf, err = runCLI([]string{"map", "lint", "--map", mapPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("map lint --json: %v (stderr=%s)", err, errBuf)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); !ok {
		t.Fatalf("expected ok=true, got %#v", report)
	}
	if report["action"] != "map_lint" {
		t.Fatalf("expected action=map_lint, got %#v", report)
	}
	if report["warning_count"] != float64(0) {
		t.Fatalf("expected warning_count=0, got %#v", report)
	}
}

func TestCLI_MapLint_WarningsOnly_Passes(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "warn.kmap")
	content := strings.Join([]string{
		"env API_KEY=exec:echo \"token\"",
		"file key.json=gcp_key",
		"envpath GOOGLE_APPLICATION_CREDENTIALS=key.json",
		"stdin exec:echo \"stdin-token\"",
	}, "\n") + "\n"
	if err := os.WriteFile(mapPath, []byte(content), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, errBuf, err := runCLI([]string{"map", "lint", "--map", mapPath}, nil)
	if err != nil {
		t.Fatalf("expected warnings-only map to pass, got err=%v stderr=%q", err, errBuf)
	}
	if !strings.Contains(out, "ok with warnings") {
		t.Fatalf("expected warnings in human output, got %q", out)
	}
	if !strings.Contains(out, "exec_source_may_require_wrapper") {
		t.Fatalf("expected exec warning in output, got %q", out)
	}

	out, errBuf, err = runCLI([]string{"map", "lint", "--map", mapPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("expected warnings-only json map to pass, got err=%v stderr=%q", err, errBuf)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); !ok {
		t.Fatalf("expected ok=true with warnings, got %#v", report)
	}
	if report["error_count"] != float64(0) {
		t.Fatalf("expected error_count=0, got %#v", report)
	}
	if wc, _ := report["warning_count"].(float64); wc < 1 {
		t.Fatalf("expected warning_count>0, got %#v", report)
	}
}

func TestCLI_MapLint_FindsIssuesAndReturnsLintExitCode(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "bad.kmap")
	content := strings.Join([]string{
		"env API_KEY=first",
		"env API_KEY=second",
		"file cfg.json=cfg1",
		"file cfg.json=cfg2",
		"envpath APP_CONFIG=missing.json",
		"envpath APP_CONFIG=cfg.json",
	}, "\n") + "\n"
	if err := os.WriteFile(mapPath, []byte(content), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, _, err := runCLI([]string{"map", "lint", "--map", mapPath}, nil)
	if err == nil {
		t.Fatalf("expected lint failure")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeMapLintFailed {
		t.Fatalf("expected lint exit code %d, got %d", exitcode.CodeMapLintFailed, ec.Code)
	}
	if !strings.Contains(out, "duplicate_env_var") {
		t.Fatalf("expected duplicate env issue in output, got %q", out)
	}
	if !strings.Contains(out, "envpath_missing_file") {
		t.Fatalf("expected missing file issue in output, got %q", out)
	}

	out, _, err = runCLI([]string{"map", "lint", "--map", mapPath, "--json"}, nil)
	if err == nil {
		t.Fatalf("expected lint failure with --json")
	}
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); ok {
		t.Fatalf("expected ok=false, got %#v", report)
	}
	if report["action"] != "map_lint" {
		t.Fatalf("expected action=map_lint, got %#v", report)
	}
	if report["error_count"] == float64(0) {
		t.Fatalf("expected non-zero error_count, got %#v", report)
	}
	issues, _ := report["issues"].([]any)
	if len(issues) == 0 {
		t.Fatalf("expected lint issues, got %#v", report)
	}
}

func TestCLI_MapLint_FilePathDirectoryConflict_IsError(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "file-conflict.kmap")
	content := strings.Join([]string{
		"file creds=secret_a",
		"file creds/token=secret_b",
	}, "\n") + "\n"
	if err := os.WriteFile(mapPath, []byte(content), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, _, err := runCLI([]string{"map", "lint", "--map", mapPath}, nil)
	if err == nil {
		t.Fatalf("expected lint failure")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeMapLintFailed {
		t.Fatalf("expected lint exit code %d, got %d", exitcode.CodeMapLintFailed, ec.Code)
	}
	if !strings.Contains(out, "file_path_conflicts_with_directory") {
		t.Fatalf("expected file path conflict issue, got %q", out)
	}
}

func TestCLI_MapLint_ProfileShadowing_IsWarningOnly(t *testing.T) {
	origWD, err := os.Getwd()
	if err != nil {
		t.Fatalf("Getwd: %v", err)
	}
	dir := t.TempDir()
	if err := os.Chdir(dir); err != nil {
		t.Fatalf("Chdir: %v", err)
	}
	t.Cleanup(func() {
		_ = os.Chdir(origWD)
	})

	localDir := filepath.Join(dir, ".kimen", "profiles")
	envDir := filepath.Join(dir, "env-profiles")
	if err := os.MkdirAll(localDir, 0o700); err != nil {
		t.Fatalf("MkdirAll local: %v", err)
	}
	if err := os.MkdirAll(envDir, 0o700); err != nil {
		t.Fatalf("MkdirAll env: %v", err)
	}
	if err := os.WriteFile(filepath.Join(localDir, "dev.kmap"), []byte("env A=a\n"), 0o600); err != nil {
		t.Fatalf("WriteFile local profile: %v", err)
	}
	if err := os.WriteFile(filepath.Join(envDir, "dev.kmap"), []byte("env A=a\n"), 0o600); err != nil {
		t.Fatalf("WriteFile env profile: %v", err)
	}

	restore := withEnv(map[string]string{
		"KIMEN_PROFILE_DIR": envDir,
	})
	defer restore()

	out, errBuf, err := runCLI([]string{"map", "lint", "--profile", "dev"}, nil)
	if err != nil {
		t.Fatalf("expected warning-only lint to pass, got err=%v stderr=%q", err, errBuf)
	}
	if !strings.Contains(out, "profile_shadowed_candidates") {
		t.Fatalf("expected profile shadowing warning, got %q", out)
	}

	out, errBuf, err = runCLI([]string{"map", "lint", "--profile", "dev", "--json"}, nil)
	if err != nil {
		t.Fatalf("expected warning-only json lint to pass, got err=%v stderr=%q", err, errBuf)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); !ok {
		t.Fatalf("expected ok=true, got %#v", report)
	}
	if report["error_count"] != float64(0) {
		t.Fatalf("expected error_count=0, got %#v", report)
	}
	if wc, _ := report["warning_count"].(float64); wc < 1 {
		t.Fatalf("expected warning_count>0, got %#v", report)
	}
}

func TestCLI_MapLint_EmptyMap_IsError(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "empty.kmap")
	if err := os.WriteFile(mapPath, []byte("# intentional blank map\n"), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, _, err := runCLI([]string{"map", "lint", "--map", mapPath}, nil)
	if err == nil {
		t.Fatalf("expected lint failure for empty map")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeMapLintFailed {
		t.Fatalf("expected lint exit code %d, got %d", exitcode.CodeMapLintFailed, ec.Code)
	}
	if !strings.Contains(out, "empty_map") {
		t.Fatalf("expected empty_map issue, got %q", out)
	}
}

func TestCLI_MapLint_InvalidProfileName(t *testing.T) {
	out, _, err := runCLI([]string{"map", "lint", "--profile", "../bad", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected lint failure for invalid profile name")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeMapLintFailed {
		t.Fatalf("expected lint exit code %d, got %d", exitcode.CodeMapLintFailed, ec.Code)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); ok {
		t.Fatalf("expected ok=false, got %#v", report)
	}
	issues, _ := report["issues"].([]any)
	if len(issues) != 1 {
		t.Fatalf("expected single issue, got %#v", report)
	}
	issue, _ := issues[0].(map[string]any)
	if issue["code"] != "invalid_input" {
		t.Fatalf("expected invalid_input issue, got %#v", issue)
	}
	msg, _ := issue["message"].(string)
	if !strings.Contains(msg, "invalid profile name") {
		t.Fatalf("expected invalid profile name message, got %#v", issue)
	}
}

func TestCLI_MapLint_EnvPathOverridesAndFileOnly_AreWarnings(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "warn-extra.kmap")
	content := strings.Join([]string{
		"env APP_CONFIG=config_secret",
		"file app/config.json=config_secret",
		"envpath APP_CONFIG=app/config.json",
	}, "\n") + "\n"
	if err := os.WriteFile(mapPath, []byte(content), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, errBuf, err := runCLI([]string{"map", "lint", "--map", mapPath}, nil)
	if err != nil {
		t.Fatalf("expected warning-only lint to pass, got err=%v stderr=%q", err, errBuf)
	}
	if !strings.Contains(out, "envpath_overrides_env_var") {
		t.Fatalf("expected envpath_overrides_env_var warning, got %q", out)
	}

	fileOnlyPath := filepath.Join(dir, "file-only.kmap")
	if err := os.WriteFile(fileOnlyPath, []byte("file only.txt=secret\n"), 0o600); err != nil {
		t.Fatalf("WriteFile(file-only): %v", err)
	}
	out, errBuf, err = runCLI([]string{"map", "lint", "--map", fileOnlyPath, "--json"}, nil)
	if err != nil {
		t.Fatalf("expected file-only warning lint to pass, got err=%v stderr=%q", err, errBuf)
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); !ok {
		t.Fatalf("expected ok=true, got %#v", report)
	}
	issues, _ := report["issues"].([]any)
	found := false
	for _, item := range issues {
		im, _ := item.(map[string]any)
		if im["code"] == "envfile_has_no_env_mappings" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("expected envfile_has_no_env_mappings warning, got %#v", report)
	}
}

func TestCLI_MapLint_StrictTreatsWarningsAsFailure(t *testing.T) {
	dir := t.TempDir()
	mapPath := filepath.Join(dir, "warn-strict.kmap")
	content := strings.Join([]string{
		"env API_KEY=exec:echo \"token\"",
		"file key.json=gcp_key",
		"envpath GOOGLE_APPLICATION_CREDENTIALS=key.json",
	}, "\n") + "\n"
	if err := os.WriteFile(mapPath, []byte(content), 0o600); err != nil {
		t.Fatalf("WriteFile: %v", err)
	}

	out, _, err := runCLI([]string{"map", "lint", "--map", mapPath, "--strict"}, nil)
	if err == nil {
		t.Fatalf("expected strict lint failure on warnings")
	}
	var ec *exitcode.Error
	if !errors.As(err, &ec) {
		t.Fatalf("expected exitcode.Error, got %T", err)
	}
	if ec.Code != exitcode.CodeMapLintFailed {
		t.Fatalf("expected lint exit code %d, got %d", exitcode.CodeMapLintFailed, ec.Code)
	}
	if !strings.Contains(out, "exec_source_may_require_wrapper") {
		t.Fatalf("expected warning detail in output, got %q", out)
	}

	out, _, err = runCLI([]string{"map", "lint", "--map", mapPath, "--strict", "--json"}, nil)
	if err == nil {
		t.Fatalf("expected strict json lint failure on warnings")
	}
	var report map[string]any
	if err := json.Unmarshal([]byte(out), &report); err != nil {
		t.Fatalf("json parse: %v", err)
	}
	if ok, _ := report["ok"].(bool); ok {
		t.Fatalf("expected ok=false in strict mode, got %#v", report)
	}
	if report["error_count"] != float64(0) {
		t.Fatalf("expected error_count=0 for warnings-only strict failure, got %#v", report)
	}
}
