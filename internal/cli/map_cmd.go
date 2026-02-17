package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
	"kimen/internal/mapfile"
)

const execPrefix = "exec:"
const profileDirEnv = "KIMEN_PROFILE_DIR"

const (
	mapLintSeverityError   = "error"
	mapLintSeverityWarning = "warning"
)

const (
	mapLintModeAll     = "all"
	mapLintModeRun     = "run"
	mapLintModeRender  = "render"
	mapLintModeEnvfile = "envfile"
)

type mapLintIssue struct {
	Code     string `json:"code"`
	Severity string `json:"severity"`
	Message  string `json:"message"`
}

type mapLintReport struct {
	OK           bool           `json:"ok"`
	Action       string         `json:"action"`
	Path         string         `json:"path,omitempty"`
	EnvCount     int            `json:"env_count"`
	FileCount    int            `json:"file_count"`
	EnvPathCount int            `json:"envpath_count"`
	ErrorCount   int            `json:"error_count"`
	WarningCount int            `json:"warning_count"`
	Issues       []mapLintIssue `json:"issues,omitempty"`
}

func newMapCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "map",
		Short: "Work with map/profile files",
	}
	cmd.AddCommand(newMapLintCommand())
	return cmd
}

func newMapLintCommand() *cobra.Command {
	var mapPath string
	var profile string
	var mode string
	var jsonOut bool
	var strict bool

	cmd := &cobra.Command{
		Use:   "lint",
		Short: "Lint a map file for common mistakes",
		RunE: func(cmd *cobra.Command, args []string) error {
			if mapPath != "" && profile != "" {
				return mapLintCommandError(cmd, jsonOut, mapLintIssue{
					Code:     "invalid_input",
					Severity: mapLintSeverityError,
					Message:  "use only one of --map or --profile",
				})
			}
			if mapPath == "" && profile == "" {
				return mapLintCommandError(cmd, jsonOut, mapLintIssue{
					Code:     "invalid_input",
					Severity: mapLintSeverityError,
					Message:  "one of --map or --profile is required",
				})
			}

			resolvedPath := strings.TrimSpace(mapPath)
			if profile != "" {
				p, err := mapfile.ResolveProfile(profile)
				if err != nil {
					return mapLintCommandError(cmd, jsonOut, mapLintIssue{Code: "invalid_input", Severity: mapLintSeverityError, Message: err.Error()})
				}
				resolvedPath = p
			}
			if strings.TrimSpace(resolvedPath) == "" {
				return mapLintCommandError(cmd, jsonOut, mapLintIssue{Code: "invalid_input", Severity: mapLintSeverityError, Message: "empty map path"})
			}
			lintMode, err := normalizeMapLintMode(mode)
			if err != nil {
				return mapLintCommandError(cmd, jsonOut, mapLintIssue{Code: "invalid_input", Severity: mapLintSeverityError, Message: err.Error()})
			}

			m, err := mapfile.ParseFile(resolvedPath)
			if err != nil {
				return mapLintCommandError(cmd, jsonOut, mapLintIssue{Code: "invalid_map", Severity: mapLintSeverityError, Message: err.Error()})
			}

			issues := lintMapForMode(m, lintMode)
			if strings.TrimSpace(profile) != "" {
				issues = append(issues, lintProfileResolutionWarnings(profile, resolvedPath)...)
			}
			sortMapLintIssues(issues)
			errorCount, warningCount := countIssuesBySeverity(issues)
			ok := errorCount == 0
			if strict && warningCount > 0 {
				ok = false
			}
			report := mapLintReport{
				OK:           ok,
				Action:       "map_lint",
				Path:         resolvedPath,
				EnvCount:     len(m.Request.Envs),
				FileCount:    len(m.Request.Files),
				EnvPathCount: len(m.EnvPaths),
				ErrorCount:   errorCount,
				WarningCount: warningCount,
				Issues:       issues,
			}

			if jsonOut {
				if err := json.NewEncoder(cmd.OutOrStdout()).Encode(report); err != nil {
					return err
				}
			} else {
				if err := printMapLintHuman(cmd, report); err != nil {
					return err
				}
			}

			if !report.OK {
				return exitcode.New(exitcode.CodeMapLintFailed, errors.New("map lint failed"))
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&mapPath, "map", "", "map file path to lint")
	cmd.Flags().StringVar(&profile, "profile", "", "profile name resolving to a map file")
	cmd.Flags().StringVar(&mode, "mode", mapLintModeAll, "lint mode (all|run|render|envfile)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&strict, "strict", false, "treat warnings as failures")
	return cmd
}

func lintMap(m mapfile.Map) []mapLintIssue {
	return lintMapForMode(m, mapLintModeAll)
}

func lintMapForMode(m mapfile.Map, mode string) []mapLintIssue {
	issues := make([]mapLintIssue, 0)

	if mapHasNoMappings(m) {
		issues = append(issues, mapLintIssue{
			Code:     "empty_map",
			Severity: mapLintSeverityError,
			Message:  "map has no mappings (add at least one env/file/stdin/envpath entry)",
		})
	}

	envConflicts, envRedundant := duplicateEnvVars(m)
	for _, k := range envConflicts {
		issues = append(issues, mapLintIssue{
			Code:     "duplicate_env_var",
			Severity: mapLintSeverityError,
			Message:  fmt.Sprintf("duplicate env var mapping with conflicting values: %s", k),
		})
	}
	for _, k := range envRedundant {
		issues = append(issues, mapLintIssue{
			Code:     "redundant_env_var",
			Severity: mapLintSeverityWarning,
			Message:  fmt.Sprintf("redundant env var mapping (same value repeated): %s", k),
		})
	}

	fileConflicts, fileRedundant := duplicateFilePaths(m)
	for _, p := range fileConflicts {
		issues = append(issues, mapLintIssue{
			Code:     "duplicate_file_path",
			Severity: mapLintSeverityError,
			Message:  fmt.Sprintf("duplicate file mapping path with conflicting values: %s", p),
		})
	}
	for _, p := range fileRedundant {
		issues = append(issues, mapLintIssue{
			Code:     "redundant_file_path",
			Severity: mapLintSeverityWarning,
			Message:  fmt.Sprintf("redundant file mapping path (same value repeated): %s", p),
		})
	}

	envPathConflicts, envPathRedundant := duplicateEnvPathVars(m)
	for _, v := range envPathConflicts {
		issues = append(issues, mapLintIssue{
			Code:     "duplicate_envpath_var",
			Severity: mapLintSeverityError,
			Message:  fmt.Sprintf("duplicate envpath var mapping with conflicting values: %s", v),
		})
	}
	for _, v := range envPathRedundant {
		issues = append(issues, mapLintIssue{
			Code:     "redundant_envpath_var",
			Severity: mapLintSeverityWarning,
			Message:  fmt.Sprintf("redundant envpath var mapping (same value repeated): %s", v),
		})
	}
	for _, v := range envPathOverridesEnvVars(m) {
		issues = append(issues, mapLintIssue{
			Code:     "envpath_overrides_env_var",
			Severity: mapLintSeverityWarning,
			Message:  fmt.Sprintf("envpath var overrides env var with the same name at runtime: %s", v),
		})
	}

	missingFiles := missingEnvPathFiles(m)
	for _, rel := range missingFiles {
		issues = append(issues, mapLintIssue{
			Code:     "envpath_missing_file",
			Severity: mapLintSeverityError,
			Message:  fmt.Sprintf("envpath refers to missing projected file: %s", rel),
		})
	}

	for _, msg := range filePathDirectoryConflicts(m) {
		issues = append(issues, mapLintIssue{
			Code:     "file_path_conflicts_with_directory",
			Severity: mapLintSeverityError,
			Message:  msg,
		})
	}

	for _, msg := range lintExecSourceEmpty(m) {
		issues = append(issues, mapLintIssue{
			Code:     "exec_source_empty_command",
			Severity: mapLintSeverityError,
			Message:  msg,
		})
	}

	if len(m.EnvPaths) > 0 && (mode == mapLintModeAll || mode == mapLintModeEnvfile) {
		issues = append(issues, mapLintIssue{
			Code:     "envpath_requires_files_dir_for_envfile",
			Severity: mapLintSeverityWarning,
			Message:  "envpath mappings require --files-dir when using `kimen envfile`",
		})
	}

	if strings.TrimSpace(m.Request.Stdin) != "" && (mode == mapLintModeAll || mode == mapLintModeRender || mode == mapLintModeEnvfile) {
		issues = append(issues, mapLintIssue{
			Code:     "stdin_run_only",
			Severity: mapLintSeverityWarning,
			Message:  "stdin mapping is only used by `kimen run` (ignored/invalid for render/envfile)",
		})
	}
	if mapHasOnlyFileMappings(m) && (mode == mapLintModeAll || mode == mapLintModeEnvfile) {
		issues = append(issues, mapLintIssue{
			Code:     "envfile_has_no_env_mappings",
			Severity: mapLintSeverityWarning,
			Message:  "map has file mappings but no env/envpath entries; `kimen envfile` with this map will fail",
		})
	}

	for _, msg := range lintExecSourcePitfalls(m) {
		issues = append(issues, mapLintIssue{
			Code:     "exec_source_may_require_wrapper",
			Severity: mapLintSeverityWarning,
			Message:  msg,
		})
	}

	sortMapLintIssues(issues)
	return issues
}

func sortMapLintIssues(issues []mapLintIssue) {
	sort.SliceStable(issues, func(i, j int) bool {
		if issues[i].Severity != issues[j].Severity {
			return severityOrder(issues[i].Severity) < severityOrder(issues[j].Severity)
		}
		if issues[i].Code == issues[j].Code {
			return issues[i].Message < issues[j].Message
		}
		return issues[i].Code < issues[j].Code
	})
}

func severityOrder(s string) int {
	switch s {
	case mapLintSeverityError:
		return 0
	case mapLintSeverityWarning:
		return 1
	default:
		return 2
	}
}

func countIssuesBySeverity(issues []mapLintIssue) (errorsN, warningsN int) {
	for _, iss := range issues {
		switch iss.Severity {
		case mapLintSeverityError:
			errorsN++
		case mapLintSeverityWarning:
			warningsN++
		}
	}
	return errorsN, warningsN
}

func duplicateEnvVars(m mapfile.Map) (conflicts []string, redundant []string) {
	seen := make(map[string]string, len(m.Request.Envs))
	conflictSet := make(map[string]struct{})
	redundantSet := make(map[string]struct{})
	for _, e := range m.Request.Envs {
		prev, ok := seen[e.Var]
		if !ok {
			seen[e.Var] = e.Name
			continue
		}
		if prev == e.Name {
			redundantSet[e.Var] = struct{}{}
			continue
		}
		conflictSet[e.Var] = struct{}{}
	}
	return sortedKeys(conflictSet), sortedKeys(redundantSet)
}

func duplicateFilePaths(m mapfile.Map) (conflicts []string, redundant []string) {
	seen := make(map[string]string, len(m.Request.Files))
	conflictSet := make(map[string]struct{})
	redundantSet := make(map[string]struct{})
	for _, f := range m.Request.Files {
		prev, ok := seen[f.RelPath]
		if !ok {
			seen[f.RelPath] = f.Name
			continue
		}
		if prev == f.Name {
			redundantSet[f.RelPath] = struct{}{}
			continue
		}
		conflictSet[f.RelPath] = struct{}{}
	}
	return sortedKeys(conflictSet), sortedKeys(redundantSet)
}

func duplicateEnvPathVars(m mapfile.Map) (conflicts []string, redundant []string) {
	seen := make(map[string]string, len(m.EnvPaths))
	conflictSet := make(map[string]struct{})
	redundantSet := make(map[string]struct{})
	for _, ep := range m.EnvPaths {
		prev, ok := seen[ep.Var]
		if !ok {
			seen[ep.Var] = ep.RelPath
			continue
		}
		if prev == ep.RelPath {
			redundantSet[ep.Var] = struct{}{}
			continue
		}
		conflictSet[ep.Var] = struct{}{}
	}
	return sortedKeys(conflictSet), sortedKeys(redundantSet)
}

func missingEnvPathFiles(m mapfile.Map) []string {
	files := make(map[string]struct{}, len(m.Request.Files))
	for _, f := range m.Request.Files {
		files[f.RelPath] = struct{}{}
	}
	missing := make(map[string]struct{})
	for _, ep := range m.EnvPaths {
		if _, ok := files[ep.RelPath]; !ok {
			missing[ep.RelPath] = struct{}{}
		}
	}
	return sortedKeys(missing)
}

func filePathDirectoryConflicts(m mapfile.Map) []string {
	if len(m.Request.Files) < 2 {
		return nil
	}
	paths := make([]string, 0, len(m.Request.Files))
	for _, f := range m.Request.Files {
		paths = append(paths, f.RelPath)
	}
	sort.Strings(paths)
	out := make(map[string]struct{})
	for i := 0; i < len(paths); i++ {
		a := paths[i]
		for j := i + 1; j < len(paths); j++ {
			b := paths[j]
			if strings.HasPrefix(b, a+"/") {
				msg := fmt.Sprintf("projected file %q conflicts with projected subpath %q (a file cannot also be a directory)", a, b)
				out[msg] = struct{}{}
			}
		}
	}
	if len(out) == 0 {
		return nil
	}
	msgs := make([]string, 0, len(out))
	for m := range out {
		msgs = append(msgs, m)
	}
	sort.Strings(msgs)
	return msgs
}

func lintExecSourceEmpty(m mapfile.Map) []string {
	issues := make(map[string]struct{})
	for _, e := range m.Request.Envs {
		if msg, ok := execSourceEmptyWarning("env "+e.Var, e.Name); ok {
			issues[msg] = struct{}{}
		}
	}
	for _, f := range m.Request.Files {
		if msg, ok := execSourceEmptyWarning("file "+f.RelPath, f.Name); ok {
			issues[msg] = struct{}{}
		}
	}
	if strings.TrimSpace(m.Request.Stdin) != "" {
		if msg, ok := execSourceEmptyWarning("stdin", m.Request.Stdin); ok {
			issues[msg] = struct{}{}
		}
	}
	if len(issues) == 0 {
		return nil
	}
	out := make([]string, 0, len(issues))
	for msg := range issues {
		out = append(out, msg)
	}
	sort.Strings(out)
	return out
}

func lintExecSourcePitfalls(m mapfile.Map) []string {
	issues := make(map[string]struct{})

	for _, e := range m.Request.Envs {
		if msg, ok := execSourceWarning("env "+e.Var, e.Name); ok {
			issues[msg] = struct{}{}
		}
	}
	for _, f := range m.Request.Files {
		if msg, ok := execSourceWarning("file "+f.RelPath, f.Name); ok {
			issues[msg] = struct{}{}
		}
	}
	if strings.TrimSpace(m.Request.Stdin) != "" {
		if msg, ok := execSourceWarning("stdin", m.Request.Stdin); ok {
			issues[msg] = struct{}{}
		}
	}

	if len(issues) == 0 {
		return nil
	}
	out := make([]string, 0, len(issues))
	for m := range issues {
		out = append(out, m)
	}
	sort.Strings(out)
	return out
}

func execSourceWarning(where, spec string) (string, bool) {
	s := strings.TrimSpace(spec)
	if !strings.HasPrefix(s, execPrefix) {
		return "", false
	}
	cmd := strings.TrimSpace(strings.TrimPrefix(s, execPrefix))
	if cmd == "" {
		return "", false
	}
	if !looksLikeShellSensitiveExec(cmd) {
		return "", false
	}
	return fmt.Sprintf("%s uses shell-sensitive exec source; `exec:` splits on whitespace and does not parse quotes (use a wrapper script): %s", where, spec), true
}

func execSourceEmptyWarning(where, spec string) (string, bool) {
	s := strings.TrimSpace(spec)
	if !strings.HasPrefix(s, execPrefix) {
		return "", false
	}
	cmd := strings.TrimSpace(strings.TrimPrefix(s, execPrefix))
	if cmd != "" {
		return "", false
	}
	return fmt.Sprintf("%s uses an empty exec source command: %s", where, spec), true
}

func looksLikeShellSensitiveExec(cmd string) bool {
	for _, r := range cmd {
		switch r {
		case '\'', '"', '|', '&', ';', '<', '>', '$', '(', ')', '`':
			return true
		}
	}
	return false
}

func sortedKeys(m map[string]struct{}) []string {
	if len(m) == 0 {
		return nil
	}
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func lintProfileResolutionWarnings(profile, resolvedPath string) []mapLintIssue {
	candidates := profileCandidatePaths(profile)
	if len(candidates) <= 1 {
		return nil
	}
	resolvedClean := filepath.Clean(resolvedPath)
	ignored := make([]string, 0, len(candidates)-1)
	for _, c := range candidates {
		if filepath.Clean(c) == resolvedClean {
			continue
		}
		ignored = append(ignored, c)
	}
	if len(ignored) == 0 {
		return nil
	}
	sort.Strings(ignored)
	return []mapLintIssue{{
		Code:     "profile_shadowed_candidates",
		Severity: mapLintSeverityWarning,
		Message: fmt.Sprintf(
			"profile %q resolved to %q, but other candidates exist and are ignored: %s",
			profile,
			resolvedPath,
			strings.Join(ignored, ", "),
		),
	}}
}

func profileCandidatePaths(profile string) []string {
	filename := profile + ".kmap"
	out := make([]string, 0, 3)

	if dir := strings.TrimSpace(os.Getenv(profileDirEnv)); dir != "" {
		p := filepath.Join(dir, filename)
		if _, err := os.Stat(p); err == nil {
			out = append(out, p)
		}
	}

	cwdPath := filepath.Join(".kimen", "profiles", filename)
	if _, err := os.Stat(cwdPath); err == nil {
		out = append(out, cwdPath)
	}

	if cfgDir, err := os.UserConfigDir(); err == nil && strings.TrimSpace(cfgDir) != "" {
		p := filepath.Join(cfgDir, "kimen", "profiles", filename)
		if _, err := os.Stat(p); err == nil {
			out = append(out, p)
		}
	}
	return out
}

func mapHasNoMappings(m mapfile.Map) bool {
	return len(m.Request.Envs) == 0 &&
		len(m.Request.Files) == 0 &&
		len(m.EnvPaths) == 0 &&
		strings.TrimSpace(m.Request.Stdin) == ""
}

func mapHasOnlyFileMappings(m mapfile.Map) bool {
	return len(m.Request.Files) > 0 &&
		len(m.Request.Envs) == 0 &&
		len(m.EnvPaths) == 0 &&
		strings.TrimSpace(m.Request.Stdin) == ""
}

func envPathOverridesEnvVars(m mapfile.Map) []string {
	if len(m.Request.Envs) == 0 || len(m.EnvPaths) == 0 {
		return nil
	}
	envVars := make(map[string]struct{}, len(m.Request.Envs))
	for _, e := range m.Request.Envs {
		envVars[e.Var] = struct{}{}
	}
	overlap := make(map[string]struct{})
	for _, ep := range m.EnvPaths {
		if _, ok := envVars[ep.Var]; ok {
			overlap[ep.Var] = struct{}{}
		}
	}
	return sortedKeys(overlap)
}

func printMapLintHuman(cmd *cobra.Command, report mapLintReport) error {
	w := cmd.OutOrStdout()
	fmt.Fprintf(w, "map: %s\n", report.Path)
	if report.OK {
		if report.WarningCount > 0 {
			fmt.Fprintf(w, "ok with warnings (%d env, %d file, %d envpath; warnings=%d)\n", report.EnvCount, report.FileCount, report.EnvPathCount, report.WarningCount)
		} else {
			fmt.Fprintf(w, "ok (%d env, %d file, %d envpath)\n", report.EnvCount, report.FileCount, report.EnvPathCount)
		}
	} else {
		fmt.Fprintf(w, "issues: %d (errors=%d warnings=%d)\n", len(report.Issues), report.ErrorCount, report.WarningCount)
	}
	for _, iss := range report.Issues {
		fmt.Fprintf(w, "- [%s:%s] %s\n", iss.Severity, iss.Code, iss.Message)
	}
	return nil
}

func mapLintCommandError(cmd *cobra.Command, jsonOut bool, issue mapLintIssue) error {
	if issue.Severity == "" {
		issue.Severity = mapLintSeverityError
	}
	report := mapLintReport{OK: false, Action: "map_lint", ErrorCount: 1, Issues: []mapLintIssue{issue}}
	if jsonOut {
		_ = json.NewEncoder(cmd.OutOrStdout()).Encode(report)
	} else {
		fmt.Fprintf(cmd.ErrOrStderr(), "%s\n", issue.Message)
	}
	return exitcode.New(exitcode.CodeMapLintFailed, errors.New(issue.Message))
}

func normalizeMapLintMode(raw string) (string, error) {
	mode := strings.ToLower(strings.TrimSpace(raw))
	if mode == "" {
		mode = mapLintModeAll
	}
	switch mode {
	case mapLintModeAll, mapLintModeRun, mapLintModeRender, mapLintModeEnvfile:
		return mode, nil
	default:
		return "", fmt.Errorf("invalid --mode %q (expected all, run, render, or envfile)", raw)
	}
}
