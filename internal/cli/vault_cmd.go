package cli

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"

	"kimen/internal/vault"
)

func newVaultCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "vault",
		Short: "Manage the local Kimen vault",
	}

	cmd.AddCommand(newVaultInitCommand())
	cmd.AddCommand(newVaultInfoCommand())
	return cmd
}

func newVaultInitCommand() *cobra.Command {
	var vaultPath string
	var passphraseStdin bool

	cmd := &cobra.Command{
		Use:   "init",
		Short: "Initialize a new vault",
		RunE: func(cmd *cobra.Command, args []string) error {
			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}
			if err := os.MkdirAll(filepath.Dir(vaultPath), 0o700); err != nil {
				return err
			}

			pp, err := (passphraseSource{fromStdin: passphraseStdin}).resolve()
			if err != nil {
				return err
			}
			defer vault.Burn(pp)

			v, err := vault.Init(vaultPath, pp)
			if err != nil {
				return err
			}
			defer v.Close()

			fmt.Fprintf(cmd.OutOrStdout(), "initialized vault at %s\n", vaultPath)
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	return cmd
}

func newVaultInfoCommand() *cobra.Command {
	var vaultPath string

	cmd := &cobra.Command{
		Use:   "info",
		Short: "Show vault metadata (no secrets)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}
			meta, err := vault.ReadMetadata(vaultPath)
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "path: %s\nformat: %s\nkdf: %s\n", vaultPath, meta.FormatVersion, meta.KDF)
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	return cmd
}
