package cli

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"

	"github.com/spf13/cobra"
	"golang.org/x/term"

	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

type secretResult struct {
	OK       bool     `json:"ok"`
	Action   string   `json:"action"`
	ExitCode int      `json:"exit_code"`
	Name     string   `json:"name,omitempty"`
	Type     string   `json:"type,omitempty"`
	From     string   `json:"from,omitempty"`
	To       string   `json:"to,omitempty"`
	Names    []string `json:"names,omitempty"`
	Count    int      `json:"count,omitempty"`
	Encoding string   `json:"encoding,omitempty"`
	ValueB64 string   `json:"value_b64,omitempty"`
}

type secretErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
	Reason   string `json:"reason,omitempty"`
}

func newSecretCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "secret",
		Short: "Manage secrets",
	}
	cmd.AddCommand(newSecretSetCommand())
	cmd.AddCommand(newSecretListCommand())
	cmd.AddCommand(newSecretGetCommand())
	cmd.AddCommand(newSecretRemoveCommand())
	cmd.AddCommand(newSecretMoveCommand())
	return cmd
}

func newSecretSetCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var fromStdin bool
	var typ string
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "set <name>",
		Short: "Set a secret value",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := strings.TrimSpace(args[0])
			if name == "" {
				return secretCommandError(cmd, errors.New("empty secret name"), jsonOut)
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return secretCommandError(cmd, err, jsonOut)
				}
				vaultPath = p
			}

			v, pp, err := openVaultWithPassphraseRetry(vaultPath, passphraseCmd, passphraseStdin)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			defer v.Close()
			defer vault.Burn(pp)

			value, err := readSecretValue(cmd, fromStdin)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			defer vault.Burn(value)

			if err := v.PutSecret(cmd.Context(), vault.Secret{
				Name:  name,
				Type:  typ,
				Value: value,
			}); err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			if jsonOut {
				return writeSecretJSON(cmd.OutOrStdout(), secretResult{OK: true, Action: "set", Name: name, Type: typ})
			}
			fmt.Fprintln(cmd.OutOrStdout(), "ok")
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&fromStdin, "stdin", false, "read secret value from stdin (raw bytes)")
	cmd.Flags().StringVar(&typ, "type", "", "secret type label (optional)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
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
					return secretCommandError(cmd, err, jsonOut)
				}
				vaultPath = p
			}
			v, pp, err := openVaultWithPassphraseRetry(vaultPath, passphraseCmd, passphraseStdin)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			defer v.Close()
			defer vault.Burn(pp)

			names, err := v.ListSecretNames(cmd.Context())
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			sort.Strings(names)

			if jsonOut {
				return writeSecretJSON(cmd.OutOrStdout(), secretResult{OK: true, Action: "list", Names: names, Count: len(names)})
			}
			for _, n := range names {
				fmt.Fprintln(cmd.OutOrStdout(), n)
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
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
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "get <name>",
		Short: "Get a secret value (disabled by default)",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !unsafe {
				return secretCommandError(cmd, errors.New("refusing to print secrets; use --unsafe-stdout if you really want this"), jsonOut)
			}
			name := strings.TrimSpace(args[0])
			if name == "" {
				return secretCommandError(cmd, errors.New("empty secret name"), jsonOut)
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return secretCommandError(cmd, err, jsonOut)
				}
				vaultPath = p
			}
			v, pp, err := openVaultWithPassphraseRetry(vaultPath, passphraseCmd, passphraseStdin)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			defer v.Close()
			defer vault.Burn(pp)

			sec, err := v.GetSecret(cmd.Context(), name)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			defer vault.Burn(sec.Value)

			if jsonOut {
				return writeSecretJSON(cmd.OutOrStdout(), secretResult{
					OK:       true,
					Action:   "get",
					Name:     name,
					Encoding: "base64",
					ValueB64: base64.RawStdEncoding.EncodeToString(sec.Value),
				})
			}

			_, err = cmd.OutOrStdout().Write(sec.Value)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&unsafe, "unsafe-stdout", false, "allow printing secrets to stdout (dangerous)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSecretRemoveCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:     "rm <name>",
		Aliases: []string{"delete"},
		Short:   "Remove a secret",
		Args:    cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := strings.TrimSpace(args[0])
			if name == "" {
				return secretCommandError(cmd, errors.New("empty secret name"), jsonOut)
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return secretCommandError(cmd, err, jsonOut)
				}
				vaultPath = p
			}
			v, pp, err := openVaultWithPassphraseRetry(vaultPath, passphraseCmd, passphraseStdin)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			defer v.Close()
			defer vault.Burn(pp)

			if err := v.DeleteSecret(cmd.Context(), name); err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			if jsonOut {
				return writeSecretJSON(cmd.OutOrStdout(), secretResult{OK: true, Action: "rm", Name: name})
			}
			fmt.Fprintln(cmd.OutOrStdout(), "ok")
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSecretMoveCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:     "mv <old-name> <new-name>",
		Aliases: []string{"rename"},
		Short:   "Rename a secret",
		Args:    cobra.ExactArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			oldName := strings.TrimSpace(args[0])
			newName := strings.TrimSpace(args[1])
			if oldName == "" || newName == "" {
				return secretCommandError(cmd, errors.New("empty secret name"), jsonOut)
			}
			if oldName == newName {
				return secretCommandError(cmd, errors.New("source and destination names must differ"), jsonOut)
			}

			if vaultPath == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return secretCommandError(cmd, err, jsonOut)
				}
				vaultPath = p
			}
			v, pp, err := openVaultWithPassphraseRetry(vaultPath, passphraseCmd, passphraseStdin)
			if err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			defer v.Close()
			defer vault.Burn(pp)

			if err := v.RenameSecret(cmd.Context(), oldName, newName); err != nil {
				return secretCommandError(cmd, err, jsonOut)
			}
			if jsonOut {
				return writeSecretJSON(cmd.OutOrStdout(), secretResult{OK: true, Action: "mv", From: oldName, To: newName})
			}
			fmt.Fprintln(cmd.OutOrStdout(), "ok")
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain passphrase (reads one line from stdout)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func writeSecretJSON(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	return enc.Encode(v)
}

func secretCommandError(cmd *cobra.Command, err error, jsonOut bool) error {
	if err == nil {
		return nil
	}
	code := secretExitCode(err)
	if jsonOut {
		_ = writeSecretJSON(cmd.ErrOrStderr(), secretErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: code,
			Reason:   secretErrorReason(err),
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(code, err)
}

func secretExitCode(err error) int {
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
		return 1
	}
}

func secretErrorReason(err error) string {
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
	case strings.Contains(msg, "empty secret name"):
		return reasonEmptySecretName
	case strings.Contains(msg, "source and destination names must differ"):
		return reasonSameSecretName
	case strings.Contains(msg, "refusing to print secrets"):
		return reasonUnsafeStdoutRequired
	case strings.Contains(msg, "empty secret value"):
		return reasonEmptySecretValue
	case strings.Contains(msg, "no secret value provided"):
		return reasonMissingSecretValue
	case strings.Contains(msg, "empty --passphrase-cmd"):
		return reasonEmptyPassphraseCommand
	case strings.Contains(msg, "passphrase command failed"):
		return reasonPassphraseCommandFailed
	case strings.Contains(msg, "no passphrase provided"):
		return reasonMissingPassphrase
	default:
		return reasonSecretFailed
	}
}
