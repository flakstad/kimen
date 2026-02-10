package cli

import (
	"errors"
	"fmt"
	"strings"

	"github.com/spf13/cobra"

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
	var passphraseStdin bool
	var envMappings []string
	var fileMappings []string
	var filesDir string

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
			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}
			pp, err := (passphraseSource{fromStdin: passphraseStdin}).resolve()
			if err != nil {
				return err
			}
			defer vault.Burn(pp)

			v, err := vault.Open(vaultPath, pp)
			if err != nil {
				return err
			}
			defer v.Close()

			req, err := projection.ParseRequest(envMappings, fileMappings)
			if err != nil {
				return err
			}
			return projection.RunCommand(cmd.Context(), v, projection.RunSpec{
				Command:  args,
				Request:  req,
				FilesDir: filesDir,
				Stdout:   cmd.OutOrStdout(),
				Stderr:   cmd.ErrOrStderr(),
				Stdin:    cmd.InOrStdin(),
			})
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringArrayVar(&envMappings, "env", nil, "env mapping VAR=secretName (repeatable)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=secretName (repeatable)")
	cmd.Flags().StringVar(&filesDir, "files-dir", "", "directory for projected files (defaults to a temp dir for this run)")
	return cmd
}

func newRenderCommand() *cobra.Command {
	var vaultPath string
	var passphraseStdin bool
	var outDir string
	var fileMappings []string

	cmd := &cobra.Command{
		Use:   "render --dir <path> --file <relpath=secretName>...",
		Short: "Render secrets into files in a directory (no lifecycle management)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(outDir) == "" {
				return errors.New("--dir is required")
			}
			if len(fileMappings) == 0 {
				return errors.New("at least one --file is required")
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}
			pp, err := (passphraseSource{fromStdin: passphraseStdin}).resolve()
			if err != nil {
				return err
			}
			defer vault.Burn(pp)

			v, err := vault.Open(vaultPath, pp)
			if err != nil {
				return err
			}
			defer v.Close()

			req, err := projection.ParseRequest(nil, fileMappings)
			if err != nil {
				return err
			}
			if len(req.Files) == 0 {
				return fmt.Errorf("no files to render")
			}

			return projection.RenderDir(cmd.Context(), v, outDir, req.Files)
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&outDir, "dir", "", "output directory (created if missing)")
	cmd.Flags().StringArrayVar(&fileMappings, "file", nil, "file mapping relpath=secretName (repeatable)")
	return cmd
}
