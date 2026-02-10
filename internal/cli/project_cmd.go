package cli

import (
	"errors"
	"fmt"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/mapfile"
	"kimen/internal/projection"
	"kimen/internal/vault"
)

func newProjectCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "project",
		Short: "Realize secrets into runtime form (explicit projection commands)",
	}
	cmd.AddCommand(newRunCommand(runUsageProject, runMissingCommandProject))
	cmd.AddCommand(newRenderCommand())
	return cmd
}

const (
	runUsageRoot             = "run -- <command> [args...]"
	runUsageProject          = "run -- <command> [args...]"
	runMissingCommandRoot    = "missing command; use `kimen run -- <command>`"
	runMissingCommandProject = "missing command; use `kimen project run -- <command>`"
)

func newRunCommand(use, missingCommandMsg string) *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var envMappings []string
	var fileMappings []string
	var envPathMappings []string
	var stdin string
	var mapPath string
	var profile string
	var filesDir string
	var dryRun bool

	cmd := &cobra.Command{
		Use:   use,
		Short: "Run a command with projected secrets (env and/or files)",
		Args: func(cmd *cobra.Command, args []string) error {
			if len(args) == 0 {
				return errors.New(missingCommandMsg)
			}
			return nil
		},
		RunE: func(cmd *cobra.Command, args []string) error {
			req, envPaths, err := resolveRunMappings(mapPath, profile, envMappings, fileMappings, envPathMappings, stdin)
			if err != nil {
				return err
			}
			if err := validateEnvPaths(req, envPaths); err != nil {
				return err
			}

			if dryRun {
				p := planFromResolved("run", args, req, envPaths, filesDir)
				return printPlanHuman(cmd, p)
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}
			pp, err := resolvePassphrase(passphraseCmd, passphraseStdin)
			if err != nil {
				return err
			}
			defer vault.Burn(pp)

			v, err := vault.Open(vaultPath, pp)
			if err != nil {
				return err
			}
			defer v.Close()

			return projection.RunCommand(cmd.Context(), v, projection.RunSpec{
				Command:  args,
				Request:  req,
				EnvPaths: envPaths,
				FilesDir: filesDir,
				Stdout:   cmd.OutOrStdout(),
				Stderr:   cmd.ErrOrStderr(),
				Stdin:    cmd.InOrStdin(),
			})
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().StringVar(&mapPath, "map", "", "map file with env/file mappings")
	cmd.Flags().StringVar(&profile, "profile", "", "named profile resolving to a map file")
	cmd.Flags().StringArrayVar(&envMappings, "env", nil, "env mapping VAR=<value> (repeatable; <value> is secret name or exec:<command...>)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=<value> (repeatable; <value> is secret name or exec:<command...>)")
	cmd.Flags().StringArrayVar(&envPathMappings, "envpath", nil, "envpath mapping VAR=relpath (repeatable)")
	cmd.Flags().StringVar(&stdin, "stdin", "", "project a value into the command stdin (<value> or exec:<command...>)")
	cmd.Flags().StringVar(&filesDir, "files-dir", "", "directory for projected files (defaults to a temp dir for this run)")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "print a plan and exit without running the command")
	return cmd
}

func newRenderCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var outDir string
	var fileMappings []string
	var mapPath string
	var profile string

	cmd := &cobra.Command{
		Use:   "render --dir <path> --file <relpath=secretName>...",
		Short: "Render secrets into files in a directory (no lifecycle management)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(outDir) == "" {
				return errors.New("--dir is required")
			}
			if len(fileMappings) == 0 && mapPath == "" && profile == "" {
				return errors.New("at least one --file is required")
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}
			pp, err := resolvePassphrase(passphraseCmd, passphraseStdin)
			if err != nil {
				return err
			}
			defer vault.Burn(pp)

			v, err := vault.Open(vaultPath, pp)
			if err != nil {
				return err
			}
			defer v.Close()

			req, _, err := resolveRunMappings(mapPath, profile, nil, fileMappings, nil, "")
			if err != nil {
				return err
			}
			if strings.TrimSpace(req.Stdin) != "" {
				return errors.New("stdin projection is only supported for `kimen run`")
			}
			if len(req.Files) == 0 {
				return fmt.Errorf("no files to render")
			}

			return projection.RenderDir(cmd.Context(), v, outDir, req.Files)
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().StringVar(&mapPath, "map", "", "map file with env/file mappings")
	cmd.Flags().StringVar(&profile, "profile", "", "named profile resolving to a map file")
	cmd.Flags().StringVar(&outDir, "dir", "", "output directory (created if missing)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=<value> (repeatable; <value> is secret name or exec:<command...>)")
	return cmd
}

func resolveRunMappings(mapPath, profile string, envMappings, fileMappings, envPathMappings []string, stdin string) (projection.Request, []projection.EnvPathMapping, error) {
	var req projection.Request
	var envPaths []projection.EnvPathMapping

	if mapPath != "" && profile != "" {
		return projection.Request{}, nil, errors.New("use only one of --map or --profile")
	}
	if profile != "" {
		p, err := mapfile.ResolveProfile(profile)
		if err != nil {
			return projection.Request{}, nil, err
		}
		mapPath = p
	}
	if mapPath != "" {
		m, err := mapfile.ParseFile(mapPath)
		if err != nil {
			return projection.Request{}, nil, err
		}
		req = m.Request
		envPaths = m.EnvPaths
	}

	inlineReq, err := projection.ParseRequest(envMappings, fileMappings, stdin)
	if err != nil {
		return projection.Request{}, nil, err
	}
	req.Envs = append(req.Envs, inlineReq.Envs...)
	req.Files = append(req.Files, inlineReq.Files...)
	if strings.TrimSpace(inlineReq.Stdin) != "" {
		if strings.TrimSpace(req.Stdin) != "" {
			return projection.Request{}, nil, errors.New("stdin projection specified multiple times (map/profile and flags)")
		}
		req.Stdin = inlineReq.Stdin
	}

	inlineEnvPaths, err := projection.ParseEnvPathMappings(envPathMappings)
	if err != nil {
		return projection.Request{}, nil, err
	}
	envPaths = append(envPaths, inlineEnvPaths...)

	return req, envPaths, nil
}
