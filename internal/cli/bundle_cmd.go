package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/spf13/cobra"

	"kimen/internal/bundle"
	"kimen/internal/exitcode"
)

type bundleResult struct {
	OK             bool   `json:"ok"`
	Action         string `json:"action"`
	IdentityPath   string `json:"identity_path,omitempty"`
	Recipient      string `json:"recipient,omitempty"`
	Vault          string `json:"vault,omitempty"`
	In             string `json:"in,omitempty"`
	Out            string `json:"out,omitempty"`
	OutVault       string `json:"out_vault,omitempty"`
	RecipientCount int    `json:"recipient_count,omitempty"`
}

type bundleErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
}

func newBundleCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "bundle",
		Short: "Export/import encrypted vault bundles for sync/CI",
	}
	cmd.AddCommand(newBundleKeygenCommand())
	cmd.AddCommand(newBundleRecipientCommand())
	cmd.AddCommand(newBundleSealCommand())
	cmd.AddCommand(newBundleOpenCommand())
	return cmd
}

func newBundleKeygenCommand() *cobra.Command {
	var outPath string
	var overwrite bool
	var printRecipient bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "keygen",
		Short: "Generate an age identity for bundle encryption/decryption",
		RunE: func(cmd *cobra.Command, args []string) error {
			if outPath == "" {
				return bundleCommandError(cmd, jsonOut, errors.New("--out is required"))
			}
			id, recipient, err := bundle.GenerateIdentityFile(outPath, overwrite)
			if err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			_ = id
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(bundleResult{
					OK:           true,
					Action:       "bundle_keygen",
					IdentityPath: outPath,
					Recipient:    recipient,
				})
			}
			if printRecipient {
				fmt.Fprintln(cmd.OutOrStdout(), recipient)
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&outPath, "out", "", "output identity file path (private key)")
	cmd.Flags().BoolVar(&overwrite, "overwrite", false, "overwrite existing identity file")
	cmd.Flags().BoolVar(&printRecipient, "print-recipient", false, "print the corresponding recipient to stdout")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newBundleRecipientCommand() *cobra.Command {
	var identityFile string
	var identityStdin bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "recipient",
		Short: "Print the age recipient for an identity file",
		RunE: func(cmd *cobra.Command, args []string) error {
			if identityFile == "" && !identityStdin {
				return bundleCommandError(cmd, jsonOut, errors.New("provide --identity or --identity-stdin"))
			}
			id, err := bundle.LoadIdentity(identityFile, identityStdin, cmd.InOrStdin())
			if err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			recipient, err := bundle.RecipientForIdentity(id)
			if err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(bundleResult{
					OK:        true,
					Action:    "bundle_recipient",
					Recipient: recipient,
				})
			}
			fmt.Fprintln(cmd.OutOrStdout(), recipient)
			return nil
		},
	}

	cmd.Flags().StringVar(&identityFile, "identity", "", "age identity file (private key)")
	cmd.Flags().BoolVar(&identityStdin, "identity-stdin", false, "read age identity from stdin")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newBundleSealCommand() *cobra.Command {
	var vaultPath string
	var outPath string
	var recipients []string
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "seal",
		Short: "Encrypt the vault file to one or more age recipients",
		RunE: func(cmd *cobra.Command, args []string) error {
			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return bundleCommandError(cmd, jsonOut, err)
				}
				vaultPath = p
			}
			if outPath == "" {
				return bundleCommandError(cmd, jsonOut, errors.New("--out is required"))
			}
			if len(recipients) == 0 {
				return bundleCommandError(cmd, jsonOut, errors.New("at least one --recipient is required"))
			}
			if err := os.MkdirAll(filepath.Dir(outPath), 0o700); err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			if err := bundle.SealVaultFile(vaultPath, outPath, recipients); err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(bundleResult{
					OK:             true,
					Action:         "bundle_seal",
					Vault:          vaultPath,
					Out:            outPath,
					RecipientCount: len(recipients),
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "sealed %s -> %s\n", vaultPath, outPath)
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().StringVar(&outPath, "out", "", "output bundle path")
	cmd.Flags().StringArrayVar(&recipients, "recipient", nil, "age recipient (repeatable)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newBundleOpenCommand() *cobra.Command {
	var inPath string
	var outVault string
	var identityFile string
	var identityStdin bool
	var overwrite bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "open",
		Short: "Decrypt an age-encrypted bundle into a vault file",
		RunE: func(cmd *cobra.Command, args []string) error {
			if inPath == "" {
				return bundleCommandError(cmd, jsonOut, errors.New("--in is required"))
			}
			if outVault == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return bundleCommandError(cmd, jsonOut, err)
				}
				outVault = p
			}
			if identityFile == "" && !identityStdin {
				return bundleCommandError(cmd, jsonOut, errors.New("provide --identity or --identity-stdin"))
			}
			if err := os.MkdirAll(filepath.Dir(outVault), 0o700); err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			id, err := bundle.LoadIdentity(identityFile, identityStdin, cmd.InOrStdin())
			if err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			if err := bundle.OpenToVaultFile(inPath, outVault, id, overwrite); err != nil {
				return bundleCommandError(cmd, jsonOut, err)
			}
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(bundleResult{
					OK:       true,
					Action:   "bundle_open",
					In:       inPath,
					OutVault: outVault,
				})
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
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func bundleCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(bundleErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: exitcode.CodeBundleFailed,
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(exitcode.CodeBundleFailed, err)
}
