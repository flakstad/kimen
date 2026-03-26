package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
	"kimen/internal/projection"
)

type planOutput struct {
	OK           bool                     `json:"ok"`
	Action       string                   `json:"action"`
	ExitCode     int                      `json:"exit_code"`
	Mode         string                   `json:"mode"`
	Command      []string                 `json:"command,omitempty"`
	Env          []projection.EnvMapping  `json:"env"`
	Files        []projection.FileMapping `json:"files"`
	Stdin        string                   `json:"stdin,omitempty"`
	EnvPaths     []planEnvPath            `json:"env_paths"`
	FilesDir     string                   `json:"files_dir,omitempty"`
	TempFilesDir bool                     `json:"temp_files_dir"`
	Cleanup      bool                     `json:"cleanup"`
	Against      string                   `json:"against,omitempty"`
	Diff         *planDiff                `json:"diff,omitempty"`
}

type planEnvPath struct {
	Var          string `json:"var"`
	RelPath      string `json:"relpath"`
	ResolvedPath string `json:"resolved_path"`
}

type planDiff struct {
	EnvAdded        []projection.EnvMapping     `json:"env_added,omitempty"`
	EnvRemoved      []projection.EnvMapping     `json:"env_removed,omitempty"`
	EnvChanged      []planEnvChange             `json:"env_changed,omitempty"`
	FilesAdded      []projection.FileMapping    `json:"files_added,omitempty"`
	FilesRemoved    []projection.FileMapping    `json:"files_removed,omitempty"`
	FilesChanged    []planFileChange            `json:"files_changed,omitempty"`
	EnvPathsAdded   []projection.EnvPathMapping `json:"envpaths_added,omitempty"`
	EnvPathsRemoved []projection.EnvPathMapping `json:"envpaths_removed,omitempty"`
	EnvPathsChanged []planEnvPathChange         `json:"envpaths_changed,omitempty"`
	StdinChanged    bool                        `json:"stdin_changed,omitempty"`
	StdinBefore     string                      `json:"stdin_before,omitempty"`
	StdinAfter      string                      `json:"stdin_after,omitempty"`
}

type planEnvChange struct {
	Var  string `json:"var"`
	From string `json:"from"`
	To   string `json:"to"`
}

type planFileChange struct {
	RelPath string `json:"relpath"`
	From    string `json:"from"`
	To      string `json:"to"`
}

type planEnvPathChange struct {
	Var  string `json:"var"`
	From string `json:"from"`
	To   string `json:"to"`
}

type planErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
	Reason   string `json:"reason,omitempty"`
}

func newPlanCommand() *cobra.Command {
	var envMappings []string
	var fileMappings []string
	var envPathMappings []string
	var stdin string
	var mapPath string
	var profile string
	var againstMap string
	var againstProfile string
	var filesDir string
	var jsonOut bool
	var mode string

	cmd := &cobra.Command{
		Use:   "plan [flags] [-- <command> [args...]]",
		Short: "Show what would be projected (no secret values)",
		Args:  cobra.ArbitraryArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			resolvedMode, err := normalizePlanMode(mode)
			if err != nil {
				return planCommandError(cmd, jsonOut, err)
			}
			req, envPaths, err := resolveRunMappings(mapPath, profile, envMappings, fileMappings, envPathMappings, stdin)
			if err != nil {
				return planCommandError(cmd, jsonOut, err)
			}
			if err := validateEnvPaths(req, envPaths); err != nil {
				return planCommandError(cmd, jsonOut, err)
			}
			p := planFromResolved(resolvedMode, args, req, envPaths, filesDir)

			againstReq, againstEnvPaths, againstLabel, hasAgainst, err := resolveAgainstMappings(againstMap, againstProfile)
			if err != nil {
				return planCommandError(cmd, jsonOut, err)
			}
			if hasAgainst {
				if err := validateEnvPaths(againstReq, againstEnvPaths); err != nil {
					return planCommandError(cmd, jsonOut, fmt.Errorf("against spec is invalid: %w", err))
				}
				d := buildPlanDiff(req, envPaths, againstReq, againstEnvPaths)
				p.Against = againstLabel
				p.Diff = &d
			}

			if jsonOut {
				enc := json.NewEncoder(cmd.OutOrStdout())
				enc.SetIndent("", "  ")
				if err := enc.Encode(p); err != nil {
					return planCommandError(cmd, jsonOut, err)
				}
				return nil
			}
			return printPlanHuman(cmd, p)
		},
	}

	cmd.Flags().StringVar(&mode, "mode", "run", "plan mode (run|render|envfile)")
	cmd.Flags().StringVar(&mapPath, "map", "", "map file with env/file mappings")
	cmd.Flags().StringVar(&profile, "profile", "", "named profile resolving to a map file")
	cmd.Flags().StringVar(&againstMap, "against-map", "", "compare current plan against another map file")
	cmd.Flags().StringVar(&againstProfile, "against-profile", "", "compare current plan against another profile")
	cmd.Flags().StringArrayVar(&envMappings, "env", nil, "env mapping VAR=<value> (repeatable; <value> is secret name [or secret:<name>], const:<literal>, or exec:<command...>)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=<value> (repeatable; <value> is secret name [or secret:<name>], const:<literal>, or exec:<command...>)")
	cmd.Flags().StringArrayVar(&envPathMappings, "envpath", nil, "envpath mapping VAR=relpath (repeatable)")
	cmd.Flags().StringVar(&stdin, "stdin", "", "show a stdin projection source (<value>, secret:<name>, const:<literal>, or exec:<command...>)")
	cmd.Flags().StringVar(&filesDir, "files-dir", "", "directory used to resolve envpath values (defaults to $KIMEN_FILES_DIR or a temp dir for run mode)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func normalizePlanMode(raw string) (string, error) {
	mode := strings.ToLower(strings.TrimSpace(raw))
	if mode == "" {
		mode = "run"
	}
	switch mode {
	case "run", "render", "envfile":
		return mode, nil
	default:
		return "", fmt.Errorf("invalid --mode %q (expected run, render, or envfile)", raw)
	}
}

func resolveAgainstMappings(againstMap, againstProfile string) (projection.Request, []projection.EnvPathMapping, string, bool, error) {
	if strings.TrimSpace(againstMap) == "" && strings.TrimSpace(againstProfile) == "" {
		return projection.Request{}, nil, "", false, nil
	}
	if strings.TrimSpace(againstMap) != "" && strings.TrimSpace(againstProfile) != "" {
		return projection.Request{}, nil, "", false, errors.New("use only one of --against-map or --against-profile")
	}

	req, envPaths, err := resolveRunMappings(againstMap, againstProfile, nil, nil, nil, "")
	if err != nil {
		return projection.Request{}, nil, "", false, err
	}

	label := againstMap
	if strings.TrimSpace(againstProfile) != "" {
		label = "profile:" + againstProfile
	}
	return req, envPaths, label, true, nil
}

func planFromResolved(mode string, command []string, req projection.Request, envPaths []projection.EnvPathMapping, filesDir string) planOutput {
	out := planOutput{
		OK:       true,
		Action:   "plan",
		ExitCode: 0,
		Mode:     mode,
		Command:  append([]string(nil), command...),
		Env:      append([]projection.EnvMapping(nil), req.Envs...),
		Files:    append([]projection.FileMapping(nil), req.Files...),
		Stdin:    strings.TrimSpace(req.Stdin),
	}

	switch mode {
	case "run":
		if len(req.Files) > 0 {
			if filesDir == "" {
				out.FilesDir = "(temp)"
				out.TempFilesDir = true
				out.Cleanup = true
			} else {
				out.FilesDir = filesDir
			}
		}
	case "render":
		out.FilesDir = filesDir
		out.Cleanup = false
	case "envfile":
		out.FilesDir = filesDir
		out.Cleanup = false
	default:
		out.Mode = "run"
		if len(req.Files) > 0 && filesDir == "" {
			out.FilesDir = "(temp)"
			out.TempFilesDir = true
			out.Cleanup = true
		} else {
			out.FilesDir = filesDir
		}
	}

	resolved := filesDir
	if resolved == "" {
		resolved = "$KIMEN_FILES_DIR"
	}
	for _, m := range envPaths {
		out.EnvPaths = append(out.EnvPaths, planEnvPath{
			Var:          m.Var,
			RelPath:      m.RelPath,
			ResolvedPath: filepath.Join(resolved, filepath.FromSlash(m.RelPath)),
		})
	}

	sort.Slice(out.Env, func(i, j int) bool { return out.Env[i].Var < out.Env[j].Var })
	sort.Slice(out.Files, func(i, j int) bool { return out.Files[i].RelPath < out.Files[j].RelPath })
	sort.Slice(out.EnvPaths, func(i, j int) bool { return out.EnvPaths[i].Var < out.EnvPaths[j].Var })

	return out
}

func printPlanHuman(cmd *cobra.Command, p planOutput) error {
	w := cmd.OutOrStdout()
	fmt.Fprintf(w, "mode: %s\n", p.Mode)
	if len(p.Command) > 0 {
		fmt.Fprintf(w, "command: %s\n", strings.Join(p.Command, " "))
	}
	if len(p.Env) > 0 {
		fmt.Fprintln(w, "env:")
		for _, e := range p.Env {
			fmt.Fprintf(w, "  - %s <= %s\n", e.Var, e.Name)
		}
	}
	if len(p.Files) > 0 {
		fmt.Fprintln(w, "files:")
		for _, f := range p.Files {
			fmt.Fprintf(w, "  - %s <= %s\n", f.RelPath, f.Name)
		}
	}
	if strings.TrimSpace(p.Stdin) != "" {
		fmt.Fprintln(w, "stdin:")
		fmt.Fprintf(w, "  - <= %s\n", p.Stdin)
	}
	if len(p.EnvPaths) > 0 {
		fmt.Fprintln(w, "envpath:")
		for _, e := range p.EnvPaths {
			fmt.Fprintf(w, "  - %s <= %s\n", e.Var, e.ResolvedPath)
		}
	}
	if p.FilesDir != "" {
		fmt.Fprintf(w, "files-dir: %s\n", p.FilesDir)
	}
	if p.TempFilesDir {
		fmt.Fprintln(w, "cleanup: yes (temp dir)")
	} else if p.Cleanup {
		fmt.Fprintln(w, "cleanup: yes")
	} else {
		fmt.Fprintln(w, "cleanup: no")
	}

	if p.Diff != nil {
		fmt.Fprintln(w, "diff:")
		if p.Against != "" {
			fmt.Fprintf(w, "  against: %s\n", p.Against)
		}
		printPlanDiffHuman(w, *p.Diff)
	}

	return nil
}

func printPlanDiffHuman(w interface{ Write([]byte) (int, error) }, d planDiff) {
	printLine := func(s string, a ...any) {
		_, _ = fmt.Fprintf(w, s, a...)
	}

	if !hasDiffChanges(d) {
		printLine("  - no changes\n")
		return
	}

	if len(d.EnvAdded) > 0 {
		printLine("  env added:\n")
		for _, e := range d.EnvAdded {
			printLine("    - %s <= %s\n", e.Var, e.Name)
		}
	}
	if len(d.EnvRemoved) > 0 {
		printLine("  env removed:\n")
		for _, e := range d.EnvRemoved {
			printLine("    - %s <= %s\n", e.Var, e.Name)
		}
	}
	if len(d.EnvChanged) > 0 {
		printLine("  env changed:\n")
		for _, e := range d.EnvChanged {
			printLine("    - %s: %s -> %s\n", e.Var, e.From, e.To)
		}
	}

	if len(d.FilesAdded) > 0 {
		printLine("  files added:\n")
		for _, f := range d.FilesAdded {
			printLine("    - %s <= %s\n", f.RelPath, f.Name)
		}
	}
	if len(d.FilesRemoved) > 0 {
		printLine("  files removed:\n")
		for _, f := range d.FilesRemoved {
			printLine("    - %s <= %s\n", f.RelPath, f.Name)
		}
	}
	if len(d.FilesChanged) > 0 {
		printLine("  files changed:\n")
		for _, f := range d.FilesChanged {
			printLine("    - %s: %s -> %s\n", f.RelPath, f.From, f.To)
		}
	}

	if len(d.EnvPathsAdded) > 0 {
		printLine("  envpath added:\n")
		for _, e := range d.EnvPathsAdded {
			printLine("    - %s <= %s\n", e.Var, e.RelPath)
		}
	}
	if len(d.EnvPathsRemoved) > 0 {
		printLine("  envpath removed:\n")
		for _, e := range d.EnvPathsRemoved {
			printLine("    - %s <= %s\n", e.Var, e.RelPath)
		}
	}
	if len(d.EnvPathsChanged) > 0 {
		printLine("  envpath changed:\n")
		for _, e := range d.EnvPathsChanged {
			printLine("    - %s: %s -> %s\n", e.Var, e.From, e.To)
		}
	}

	if d.StdinChanged {
		printLine("  stdin changed: %s -> %s\n", displayStdinValue(d.StdinBefore), displayStdinValue(d.StdinAfter))
	}
}

func hasDiffChanges(d planDiff) bool {
	return len(d.EnvAdded) > 0 ||
		len(d.EnvRemoved) > 0 ||
		len(d.EnvChanged) > 0 ||
		len(d.FilesAdded) > 0 ||
		len(d.FilesRemoved) > 0 ||
		len(d.FilesChanged) > 0 ||
		len(d.EnvPathsAdded) > 0 ||
		len(d.EnvPathsRemoved) > 0 ||
		len(d.EnvPathsChanged) > 0 ||
		d.StdinChanged
}

func displayStdinValue(v string) string {
	if strings.TrimSpace(v) == "" {
		return "(empty)"
	}
	return v
}

func buildPlanDiff(currentReq projection.Request, currentEnvPaths []projection.EnvPathMapping, baseReq projection.Request, baseEnvPaths []projection.EnvPathMapping) planDiff {
	currentEnv := effectiveEnvMap(currentReq.Envs)
	baseEnv := effectiveEnvMap(baseReq.Envs)

	currentFiles := effectiveFileMap(currentReq.Files)
	baseFiles := effectiveFileMap(baseReq.Files)

	currentEnvPath := effectiveEnvPathMap(currentEnvPaths)
	baseEnvPath := effectiveEnvPathMap(baseEnvPaths)

	envAdded, envRemoved, envChanged := diffStringMaps(currentEnv, baseEnv)
	fileAdded, fileRemoved, fileChanged := diffStringMaps(currentFiles, baseFiles)
	envPathAdded, envPathRemoved, envPathChanged := diffStringMaps(currentEnvPath, baseEnvPath)

	d := planDiff{
		EnvAdded:        mapToEnvMappings(envAdded),
		EnvRemoved:      mapToEnvMappings(envRemoved),
		EnvChanged:      mapToEnvChanges(envChanged),
		FilesAdded:      mapToFileMappings(fileAdded),
		FilesRemoved:    mapToFileMappings(fileRemoved),
		FilesChanged:    mapToFileChanges(fileChanged),
		EnvPathsAdded:   mapToEnvPathMappings(envPathAdded),
		EnvPathsRemoved: mapToEnvPathMappings(envPathRemoved),
		EnvPathsChanged: mapToEnvPathChanges(envPathChanged),
	}

	curStdin := strings.TrimSpace(currentReq.Stdin)
	baseStdin := strings.TrimSpace(baseReq.Stdin)
	if curStdin != baseStdin {
		d.StdinChanged = true
		d.StdinBefore = baseStdin
		d.StdinAfter = curStdin
	}

	return d
}

type stringMapChange struct {
	Key  string
	From string
	To   string
}

func diffStringMaps(current, baseline map[string]string) (added, removed map[string]string, changed []stringMapChange) {
	added = make(map[string]string)
	removed = make(map[string]string)
	changed = make([]stringMapChange, 0)

	for k, cur := range current {
		base, ok := baseline[k]
		if !ok {
			added[k] = cur
			continue
		}
		if cur != base {
			changed = append(changed, stringMapChange{Key: k, From: base, To: cur})
		}
	}

	for k, base := range baseline {
		if _, ok := current[k]; !ok {
			removed[k] = base
		}
	}

	sort.Slice(changed, func(i, j int) bool { return changed[i].Key < changed[j].Key })
	return added, removed, changed
}

func effectiveEnvMap(envs []projection.EnvMapping) map[string]string {
	m := make(map[string]string, len(envs))
	for _, e := range envs {
		m[e.Var] = e.Name
	}
	return m
}

func effectiveFileMap(files []projection.FileMapping) map[string]string {
	m := make(map[string]string, len(files))
	for _, f := range files {
		m[f.RelPath] = f.Name
	}
	return m
}

func effectiveEnvPathMap(paths []projection.EnvPathMapping) map[string]string {
	m := make(map[string]string, len(paths))
	for _, p := range paths {
		m[p.Var] = p.RelPath
	}
	return m
}

func mapToEnvMappings(m map[string]string) []projection.EnvMapping {
	if len(m) == 0 {
		return nil
	}
	keys := sortedStringKeys(m)
	out := make([]projection.EnvMapping, 0, len(keys))
	for _, k := range keys {
		out = append(out, projection.EnvMapping{Var: k, Name: m[k]})
	}
	return out
}

func mapToFileMappings(m map[string]string) []projection.FileMapping {
	if len(m) == 0 {
		return nil
	}
	keys := sortedStringKeys(m)
	out := make([]projection.FileMapping, 0, len(keys))
	for _, k := range keys {
		out = append(out, projection.FileMapping{RelPath: k, Name: m[k]})
	}
	return out
}

func mapToEnvPathMappings(m map[string]string) []projection.EnvPathMapping {
	if len(m) == 0 {
		return nil
	}
	keys := sortedStringKeys(m)
	out := make([]projection.EnvPathMapping, 0, len(keys))
	for _, k := range keys {
		out = append(out, projection.EnvPathMapping{Var: k, RelPath: m[k]})
	}
	return out
}

func mapToEnvChanges(ch []stringMapChange) []planEnvChange {
	if len(ch) == 0 {
		return nil
	}
	out := make([]planEnvChange, 0, len(ch))
	for _, c := range ch {
		out = append(out, planEnvChange{Var: c.Key, From: c.From, To: c.To})
	}
	return out
}

func mapToFileChanges(ch []stringMapChange) []planFileChange {
	if len(ch) == 0 {
		return nil
	}
	out := make([]planFileChange, 0, len(ch))
	for _, c := range ch {
		out = append(out, planFileChange{RelPath: c.Key, From: c.From, To: c.To})
	}
	return out
}

func mapToEnvPathChanges(ch []stringMapChange) []planEnvPathChange {
	if len(ch) == 0 {
		return nil
	}
	out := make([]planEnvPathChange, 0, len(ch))
	for _, c := range ch {
		out = append(out, planEnvPathChange{Var: c.Key, From: c.From, To: c.To})
	}
	return out
}

func sortedStringKeys(m map[string]string) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func planCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(planErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: exitcode.CodePlanFailed,
			Reason:   planErrorReason(err),
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(exitcode.CodePlanFailed, err)
}

func planErrorReason(err error) string {
	if err == nil {
		return ""
	}
	msg := strings.ToLower(strings.TrimSpace(err.Error()))
	switch {
	case strings.Contains(msg, "invalid --mode"):
		return reasonInvalidMode
	case strings.Contains(msg, "invalid profile name"):
		return reasonInvalidProfileName
	case strings.Contains(msg, "use only one of --map or --profile"):
		return reasonConflictingMapProfileInputs
	case strings.Contains(msg, "use only one of --against-map or --against-profile"):
		return reasonConflictingAgainstInputs
	case strings.Contains(msg, "against spec is invalid"):
		return reasonInvalidAgainstSpec
	case strings.Contains(msg, "envpath mappings require projected files"):
		return reasonEnvpathRequiresProjectedFiles
	case strings.Contains(msg, "envpath refers to missing projected file"):
		return reasonEnvpathMissingProjectedFile
	default:
		return reasonPlanFailed
	}
}

func validateEnvPaths(req projection.Request, envPaths []projection.EnvPathMapping) error {
	if len(envPaths) == 0 {
		return nil
	}
	if len(req.Files) == 0 {
		return errors.New("envpath mappings require projected files (add --file entries)")
	}
	files := make(map[string]struct{}, len(req.Files))
	for _, f := range req.Files {
		files[f.RelPath] = struct{}{}
	}
	var missing []string
	for _, ep := range envPaths {
		if _, ok := files[ep.RelPath]; !ok {
			missing = append(missing, ep.RelPath)
		}
	}
	if len(missing) > 0 {
		sort.Strings(missing)
		return fmt.Errorf("envpath refers to missing projected file(s): %s", strings.Join(missing, ", "))
	}
	return nil
}
