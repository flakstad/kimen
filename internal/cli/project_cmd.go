package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
	"kimen/internal/mapfile"
	"kimen/internal/projection"
	"kimen/internal/vault"
)

type projectionResult struct {
	OK        bool     `json:"ok"`
	Action    string   `json:"action"`
	ExitCode  int      `json:"exit_code"`
	OutDir    string   `json:"out_dir,omitempty"`
	FileCount int      `json:"file_count,omitempty"`
	Hints     []string `json:"hints,omitempty"`
}

type projectionErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
	Reason   string `json:"reason,omitempty"`
}

var systemdServiceNameRE = regexp.MustCompile(`^[A-Za-z0-9_.@-]+$`)

func newProjectCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "project",
		Short: "Realize secrets into runtime form (explicit projection commands)",
	}
	cmd.AddCommand(newRunCommand(runUsageProject, runMissingCommandProject))
	cmd.AddCommand(newRenderCommand())
	cmd.AddCommand(newPlanCommand())
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
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   use,
		Short: "Run a command with projected secrets (env and/or files)",
		Args: func(cmd *cobra.Command, args []string) error {
			if len(args) == 0 {
				return projectionCommandError(cmd, jsonOut, errors.New(missingCommandMsg))
			}
			return nil
		},
		RunE: func(cmd *cobra.Command, args []string) error {
			req, envPaths, err := resolveRunMappings(mapPath, profile, envMappings, fileMappings, envPathMappings, stdin)
			if err != nil {
				return projectionCommandError(cmd, jsonOut, err)
			}
			if err := validateEnvPaths(req, envPaths); err != nil {
				return projectionCommandError(cmd, jsonOut, err)
			}

			if dryRun {
				p := planFromResolved("run", args, req, envPaths, filesDir)
				if jsonOut {
					enc := json.NewEncoder(cmd.OutOrStdout())
					enc.SetIndent("", "  ")
					if err := enc.Encode(p); err != nil {
						return projectionCommandError(cmd, jsonOut, err)
					}
					return nil
				}
				if err := printPlanHuman(cmd, p); err != nil {
					return projectionCommandError(cmd, jsonOut, err)
				}
				return nil
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return projectionCommandError(cmd, jsonOut, err)
				}
				vaultPath = p
			}
			v, pp, err := openVaultWithPassphraseRetry(vaultPath, passphraseCmd, passphraseStdin)
			if err != nil {
				return projectionCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(pp)
			vaultClosed := false
			defer func() {
				if !vaultClosed {
					_ = v.Close()
				}
			}()

			err = projection.RunCommand(cmd.Context(), v, projection.RunSpec{
				Command:  args,
				Request:  req,
				EnvPaths: envPaths,
				FilesDir: filesDir,
				Stdout:   cmd.OutOrStdout(),
				Stderr:   cmd.ErrOrStderr(),
				Stdin:    cmd.InOrStdin(),
				BeforeExec: func() error {
					if err := v.Close(); err != nil {
						return err
					}
					vaultClosed = true
					return nil
				},
			})
			if err != nil {
				return projectionCommandError(cmd, jsonOut, err)
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().StringVar(&mapPath, "map", "", "map file with env/file mappings")
	cmd.Flags().StringVar(&profile, "profile", "", "named profile resolving to a map file")
	cmd.Flags().StringArrayVar(&envMappings, "env", nil, "env mapping VAR=<value> (repeatable; <value> is secret name [or secret:<name>], const:<literal>, or exec:<command...>)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=<value> (repeatable; <value> is secret name [or secret:<name>], const:<literal>, or exec:<command...>)")
	cmd.Flags().StringArrayVar(&envPathMappings, "envpath", nil, "envpath mapping VAR=relpath (repeatable)")
	cmd.Flags().StringVar(&stdin, "stdin", "", "project a value into the command stdin (<value>, secret:<name>, const:<literal>, or exec:<command...>)")
	cmd.Flags().StringVar(&filesDir, "files-dir", "", "directory for projected files (defaults to a temp dir for this run)")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "print a plan and exit without running the command")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON where applicable (dry-run plan and structured errors)")
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
	var jsonOut bool
	var systemdService string
	var runtimeDir string
	var printSystemdHints bool

	cmd := &cobra.Command{
		Use:   "render --dir <path> --file <relpath=secretName>...",
		Short: "Render secrets into files in a directory (no lifecycle management)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(systemdService) != "" && strings.TrimSpace(outDir) != "" {
				return projectionCommandError(cmd, jsonOut, errors.New("use only one of --dir or --systemd-service"))
			}
			if strings.TrimSpace(outDir) == "" && strings.TrimSpace(systemdService) == "" {
				return projectionCommandError(cmd, jsonOut, errors.New("--dir is required (or use --systemd-service)"))
			}
			if strings.TrimSpace(systemdService) == "" && printSystemdHints {
				return projectionCommandError(cmd, jsonOut, errors.New("--print-systemd-hints requires --systemd-service"))
			}
			if len(fileMappings) == 0 && mapPath == "" && profile == "" {
				return projectionCommandError(cmd, jsonOut, errors.New("at least one --file is required"))
			}

			if strings.TrimSpace(systemdService) != "" {
				service := strings.TrimSpace(systemdService)
				if !systemdServiceNameRE.MatchString(service) {
					return projectionCommandError(cmd, jsonOut, fmt.Errorf("invalid --systemd-service %q", service))
				}
				base := strings.TrimSpace(runtimeDir)
				if base == "" {
					base = "/run"
				}
				outDir = filepath.Join(base, "kimen", service)
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return projectionCommandError(cmd, jsonOut, err)
				}
				vaultPath = p
			}
			v, pp, err := openVaultWithPassphraseRetry(vaultPath, passphraseCmd, passphraseStdin)
			if err != nil {
				return projectionCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(pp)
			defer v.Close()

			req, _, err := resolveRunMappings(mapPath, profile, nil, fileMappings, nil, "")
			if err != nil {
				return projectionCommandError(cmd, jsonOut, err)
			}
			if strings.TrimSpace(req.Stdin) != "" {
				return projectionCommandError(cmd, jsonOut, errors.New("stdin projection is only supported for `kimen run`"))
			}
			if len(req.Files) == 0 {
				return projectionCommandError(cmd, jsonOut, fmt.Errorf("no files to render"))
			}

			if err := projection.RenderDir(cmd.Context(), v, outDir, req.Files); err != nil {
				return projectionCommandError(cmd, jsonOut, err)
			}

			hints := []string(nil)
			if strings.TrimSpace(systemdService) != "" && printSystemdHints {
				hints = buildSystemdRenderHints(outDir)
			}

			if len(hints) > 0 && !jsonOut {
				for _, h := range hints {
					fmt.Fprintln(cmd.OutOrStdout(), h)
				}
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(projectionResult{
					OK:        true,
					Action:    "render",
					OutDir:    outDir,
					FileCount: len(req.Files),
					Hints:     hints,
				})
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().StringVar(&mapPath, "map", "", "map file with env/file mappings")
	cmd.Flags().StringVar(&profile, "profile", "", "named profile resolving to a map file")
	cmd.Flags().StringVar(&outDir, "dir", "", "output directory (created if missing)")
	cmd.Flags().StringVar(&systemdService, "systemd-service", "", "render to a systemd-friendly runtime path (<runtime-dir>/kimen/<service>)")
	cmd.Flags().StringVar(&runtimeDir, "runtime-dir", "/run", "base runtime directory used with --systemd-service")
	cmd.Flags().BoolVar(&printSystemdHints, "print-systemd-hints", false, "print service wiring hints when using --systemd-service")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=<value> (repeatable; <value> is secret name [or secret:<name>], const:<literal>, or exec:<command...>)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
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

func projectionCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	var ec *exitcode.Error
	if errors.As(err, &ec) {
		return err
	}
	code := projectionExitCode(err)
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(projectionErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: code,
			Reason:   projectionErrorReason(err),
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(code, err)
}

func projectionErrorReason(err error) string {
	if err == nil {
		return ""
	}
	switch {
	case errors.Is(err, vault.ErrSecretNotFound):
		return reasonSecretNotFound
	case errors.Is(err, vault.ErrSecretExists):
		return reasonSecretExists
	case errors.Is(err, vault.ErrVaultNotFound):
		return reasonVaultNotFound
	case errors.Is(err, vault.ErrWrongPassphrase):
		return reasonWrongPassphrase
	}

	msg := strings.ToLower(strings.TrimSpace(err.Error()))
	switch {
	case strings.Contains(msg, "missing command"):
		return reasonMissingCommand
	case strings.Contains(msg, "use only one of --map or --profile"):
		return reasonConflictingMapProfileInputs
	case strings.Contains(msg, "invalid profile name"):
		return reasonInvalidProfileName
	case strings.Contains(msg, "stdin projection specified multiple times"):
		return reasonConflictingStdinInputs
	case strings.Contains(msg, "use only one of --dir or --systemd-service"):
		return reasonConflictingRenderTargetInputs
	case strings.Contains(msg, "--dir is required (or use --systemd-service)"):
		return reasonMissingRenderTarget
	case strings.Contains(msg, "--print-systemd-hints requires --systemd-service"):
		return reasonSystemdHintsRequiresService
	case strings.Contains(msg, "at least one --file is required"):
		return reasonMissingFileMappings
	case strings.Contains(msg, "invalid --systemd-service"):
		return reasonInvalidSystemdService
	case strings.Contains(msg, "stdin projection is only supported for `kimen run`"):
		return reasonStdinNotSupported
	case strings.Contains(msg, "no files to render"):
		return reasonNoFilesToRender
	case strings.Contains(msg, "envpath mappings require projected files"):
		return reasonEnvpathRequiresProjectedFiles
	case strings.Contains(msg, "envpath refers to missing projected file"):
		return reasonEnvpathMissingProjectedFile
	case strings.Contains(msg, "invalid --env mapping"),
		strings.Contains(msg, "invalid --file mapping"),
		strings.Contains(msg, "invalid envpath mapping"):
		return reasonInvalidMapping
	case strings.Contains(msg, "invalid env var name"):
		return reasonInvalidEnvVar
	case strings.Contains(msg, "invalid relative path"):
		return reasonInvalidRelativePath
	default:
		return reasonProjectionFailed
	}
}

func projectionExitCode(err error) int {
	switch {
	case errors.Is(err, vault.ErrSecretNotFound):
		return exitcode.CodeSecretNotFound
	case errors.Is(err, vault.ErrSecretExists):
		return exitcode.CodeSecretExists
	case errors.Is(err, vault.ErrVaultNotFound):
		return exitcode.CodeVaultNotFound
	case errors.Is(err, vault.ErrWrongPassphrase):
		return exitcode.CodeWrongPassphrase
	default:
		return exitcode.CodeProjectionFailed
	}
}

func buildSystemdRenderHints(outDir string) []string {
	return []string{
		fmt.Sprintf("Environment=KIMEN_FILES_DIR=%s", outDir),
		fmt.Sprintf("# files rendered under %s (dir 0700, files 0600)", outDir),
	}
}
