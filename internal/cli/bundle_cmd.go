package cli

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"

	"kimen/internal/bundle"
)

func newBundleCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "bundle",
		Short: "Export/import encrypted vault bundles for sync/CI",
	}
	cmd.AddCommand(newBundleSealCommand())
	cmd.AddCommand(newBundleOpenCommand())
	return cmd
}

func newBundleSealCommand() *cobra.Command {
	var vaultPath string
	var outPath string
	var recipients []string

	cmd := &cobra.Command{
		Use:   "seal",
		Short: "Encrypt the vault file to one or more age recipients",
		RunE: func(cmd *cobra.Command, args []string) error {
			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				vaultPath = p
			}
			if outPath == "" {
				return errors.New("--out is required")
			}
			if len(recipients) == 0 {
				return errors.New("at least one --recipient is required")
			}
			if err := os.MkdirAll(filepath.Dir(outPath), 0o700); err != nil {
				return err
			}
			if err := bundle.SealVaultFile(vaultPath, outPath, recipients); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "sealed %s -> %s\n", vaultPath, outPath)
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().StringVar(&outPath, "out", "", "output bundle path")
	cmd.Flags().StringArrayVar(&recipients, "recipient", nil, "age recipient (repeatable)")
	return cmd
}

func newBundleOpenCommand() *cobra.Command {
	var inPath string
	var outVault string
	var identityFile string
	var identityStdin bool
	var overwrite bool

	cmd := &cobra.Command{
		Use:   "open",
		Short: "Decrypt an age-encrypted bundle into a vault file",
		RunE: func(cmd *cobra.Command, args []string) error {
			if inPath == "" {
				return errors.New("--in is required")
			}
			if outVault == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return err
				}
				outVault = p
			}
			if identityFile == "" && !identityStdin {
				return errors.New("provide --identity or --identity-stdin")
			}
			if err := os.MkdirAll(filepath.Dir(outVault), 0o700); err != nil {
				return err
			}
			id, err := bundle.LoadIdentity(identityFile, identityStdin, os.Stdin)
			if err != nil {
				return err
			}
			if err := bundle.OpenToVaultFile(inPath, outVault, id, overwrite); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "opened %s -> %s\n", inPath, outVault)
			return nil
		},
	}

	cmd.Flags().StringVar(&inPath, "in", "", "input bundle path")
	cmd.Flags().StringVar(&outVault, "out-vault", "", "output vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().StringVar(&identityFile, "identity", "", "age identity file (private key)")
	cmd.Flags().BoolVar(&identityStdin, "identity-stdin", false, "read age identity from stdin")
	cmd.Flags().BoolVar(&overwrite, "overwrite", false, "overwrite existing vault file")
	return cmd
}
