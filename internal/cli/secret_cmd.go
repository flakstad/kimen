package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"

	"github.com/spf13/cobra"
	"golang.org/x/term"

	"kimen/internal/vault"
)

func newSecretCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "secret",
		Short: "Manage secrets",
	}
	cmd.AddCommand(newSecretSetCommand())
	cmd.AddCommand(newSecretListCommand())
	cmd.AddCommand(newSecretGetCommand())
	return cmd
}

func newSecretSetCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var fromStdin bool
	var typ string

	cmd := &cobra.Command{
		Use:   "set <name>",
		Short: "Set a secret value",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
			if strings.TrimSpace(name) == "" {
				return errors.New("empty secret name")
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

			value, err := readSecretValue(cmd, fromStdin)
			if err != nil {
				return err
			}
			defer vault.Burn(value)

			if err := v.PutSecret(cmd.Context(), vault.Secret{
				Name:  name,
				Type:  typ,
				Value: value,
			}); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "ok\n")
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&fromStdin, "stdin", false, "read secret value from stdin (raw bytes)")
	cmd.Flags().StringVar(&typ, "type", "", "secret type label (optional)")
	return cmd
}

func readSecretValue(cmd *cobra.Command, fromStdin bool) ([]byte, error) {
	if fromStdin {
		return io.ReadAll(cmd.InOrStdin())
	}
	if term.IsTerminal(int(os.Stdin.Fd())) {
		fmt.Fprint(cmd.ErrOrStderr(), "Secret value: ")
		b, err := term.ReadPassword(int(os.Stdin.Fd()))
		fmt.Fprintln(cmd.ErrOrStderr())
		if err != nil {
			return nil, err
		}
		if len(b) == 0 {
			return nil, errors.New("empty secret value")
		}
		return b, nil
	}
	return nil, errors.New("no secret value provided (use --stdin or run in a tty)")
}

func newSecretListCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "list",
		Short: "List secret names (requires unlock)",
		RunE: func(cmd *cobra.Command, args []string) error {
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

			names, err := v.ListSecretNames(cmd.Context())
			if err != nil {
				return err
			}
			sort.Strings(names)

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(names)
			}
			for _, n := range names {
				fmt.Fprintln(cmd.OutOrStdout(), n)
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSecretGetCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var unsafe bool

	cmd := &cobra.Command{
		Use:   "get <name>",
		Short: "Get a secret value (disabled by default)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !unsafe {
				return errors.New("refusing to print secrets; use --unsafe-stdout if you really want this")
			}
			name := args[0]

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

			sec, err := v.GetSecret(cmd.Context(), name)
			if err != nil {
				return err
			}
			defer vault.Burn(sec.Value)

			_, err = cmd.OutOrStdout().Write(sec.Value)
			return err
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&unsafe, "unsafe-stdout", false, "allow printing secrets to stdout (dangerous)")
	return cmd
}
