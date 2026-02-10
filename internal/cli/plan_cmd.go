package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"sort"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/projection"
)

type planOutput struct {
	Mode         string                   `json:"mode"`
	Command      []string                 `json:"command,omitempty"`
	Env          []projection.EnvMapping  `json:"env"`
	Files        []projection.FileMapping `json:"files"`
	Stdin        string                   `json:"stdin,omitempty"`
	EnvPaths     []planEnvPath            `json:"env_paths"`
	FilesDir     string                   `json:"files_dir,omitempty"`
	TempFilesDir bool                     `json:"temp_files_dir"`
	Cleanup      bool                     `json:"cleanup"`
}

type planEnvPath struct {
	Var          string `json:"var"`
	RelPath      string `json:"relpath"`
	ResolvedPath string `json:"resolved_path"`
}

func newPlanCommand() *cobra.Command {
	var envMappings []string
	var fileMappings []string
	var envPathMappings []string
	var stdin string
	var mapPath string
	var profile string
	var filesDir string
	var jsonOut bool
	var mode string

	cmd := &cobra.Command{
		Use:   "plan [flags] [-- <command> [args...]]",
		Short: "Show what would be projected (no secret values)",
		Args:  cobra.ArbitraryArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			req, envPaths, err := resolveRunMappings(mapPath, profile, envMappings, fileMappings, envPathMappings, stdin)
			if err != nil {
				return err
			}
			if err := validateEnvPaths(req, envPaths); err != nil {
				return err
			}
			p := planFromResolved(mode, args, req, envPaths, filesDir)
			if jsonOut {
				enc := json.NewEncoder(cmd.OutOrStdout())
				enc.SetIndent("", "  ")
				return enc.Encode(p)
			}
			return printPlanHuman(cmd, p)
		},
	}

	cmd.Flags().StringVar(&mode, "mode", "run", "plan mode (run|render|envfile)")
	cmd.Flags().StringVar(&mapPath, "map", "", "map file with env/file mappings")
	cmd.Flags().StringVar(&profile, "profile", "", "named profile resolving to a map file")
	cmd.Flags().StringArrayVar(&envMappings, "env", nil, "env mapping VAR=<value> (repeatable; <value> is secret name or exec:<command...>)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=<value> (repeatable; <value> is secret name or exec:<command...>)")
	cmd.Flags().StringArrayVar(&envPathMappings, "envpath", nil, "envpath mapping VAR=relpath (repeatable)")
	cmd.Flags().StringVar(&stdin, "stdin", "", "show a stdin projection source (<value> or exec:<command...>)")
	cmd.Flags().StringVar(&filesDir, "files-dir", "", "directory used to resolve envpath values (defaults to $KIMEN_FILES_DIR or a temp dir for run mode)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func planFromResolved(mode string, command []string, req projection.Request, envPaths []projection.EnvPathMapping, filesDir string) planOutput {
	out := planOutput{
		Mode:    mode,
		Command: append([]string(nil), command...),
		Env:     append([]projection.EnvMapping(nil), req.Envs...),
		Files:   append([]projection.FileMapping(nil), req.Files...),
		Stdin:   strings.TrimSpace(req.Stdin),
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
	return nil
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
