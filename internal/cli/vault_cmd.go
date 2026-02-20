package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"

	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

type vaultResult struct {
	OK       bool   `json:"ok"`
	Action   string `json:"action"`
	ExitCode int    `json:"exit_code"`
	Path     string `json:"path,omitempty"`
	Source   string `json:"source,omitempty"`
	Format   string `json:"format,omitempty"`
	KDF      string `json:"kdf,omitempty"`
}

type vaultErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
	Reason   string `json:"reason,omitempty"`
}

func newVaultCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "vault",
		Short: "Manage the local Kimen vault",
	}

	cmd.AddCommand(newVaultInitCommand())
	cmd.AddCommand(newVaultPathCommand())
	cmd.AddCommand(newVaultInfoCommand())
	return cmd
}

func newVaultInitCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "init",
		Short: "Initialize a new vault",
		RunE: func(cmd *cobra.Command, args []string) error {
			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return vaultCommandError(cmd, jsonOut, err)
				}
				vaultPath = p
			}
			if err := os.MkdirAll(filepath.Dir(vaultPath), 0o700); err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}

			pp, err := resolvePassphrase(passphraseCmd, passphraseStdin)
			if err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(pp)

			v, err := vault.Init(vaultPath, pp)
			if err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}
			defer v.Close()

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(vaultResult{
					OK:     true,
					Action: "vault_init",
					Path:   vaultPath,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "initialized vault at %s\n", vaultPath)
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newVaultInfoCommand() *cobra.Command {
	var vaultPath string
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "info",
		Short: "Show vault metadata (no secrets)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return vaultCommandError(cmd, jsonOut, err)
				}
				vaultPath = p
			}
			meta, err := vault.ReadMetadata(vaultPath)
			if err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(vaultResult{
					OK:     true,
					Action: "vault_info",
					Path:   vaultPath,
					Format: meta.FormatVersion,
					KDF:    meta.KDF,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "path: %s\nformat: %s\nkdf: %s\n", vaultPath, meta.FormatVersion, meta.KDF)
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newVaultPathCommand() *cobra.Command {
	var vaultPath string
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "path",
		Short: "Show the resolved vault path",
		RunE: func(cmd *cobra.Command, args []string) error {
			p, source, err := resolveVaultPath(vaultPath)
			if err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(vaultResult{
					OK:     true,
					Action: "vault_path",
					Path:   p,
					Source: source,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "path: %s\nsource: %s\n", p, source)
			return nil
		},
	}
	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path override")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func vaultCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	code := vaultExitCodeForError(err)
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(vaultErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: code,
			Reason:   vaultErrorReason(err),
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(code, err)
}

func vaultExitCodeForError(err error) int {
	switch {
	case errors.Is(err, vault.ErrVaultNotFound):
		return exitcode.CodeVaultNotFound
	case errors.Is(err, vault.ErrWrongPassphrase):
		return exitcode.CodeWrongPassphrase
	default:
		return exitcode.CodeVaultFailed
	}
}

func vaultErrorReason(err error) string {
	if err == nil {
		return ""
	}
	switch {
	case errors.Is(err, vault.ErrVaultNotFound):
		return reasonVaultNotFound
	case errors.Is(err, vault.ErrWrongPassphrase):
		return reasonWrongPassphrase
	case errors.Is(err, vault.ErrInvalidVaultFile):
		return reasonInvalidVaultFile
	}
	msg := strings.ToLower(strings.TrimSpace(err.Error()))
	switch {
	case strings.Contains(msg, "vault already exists"):
		return reasonVaultExists
	case strings.Contains(msg, "empty --passphrase-cmd"):
		return reasonEmptyPassphraseCommand
	case strings.Contains(msg, "passphrase command failed"):
		return reasonPassphraseCommandFailed
	case strings.Contains(msg, "no passphrase provided"):
		return reasonMissingPassphrase
	default:
		return reasonVaultFailed
	}
}
