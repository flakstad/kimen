package cli

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"golang.org/x/term"

	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

var (
	errConflictingOldPassphraseInputs = errors.New("use only one old passphrase source: --passphrase-cmd, --passphrase-stdin, or --old-passphrase-file")
	errConflictingNewPassphraseInputs = errors.New("use only one new passphrase source: --new-passphrase-cmd, --new-passphrase-stdin, --new-passphrase-file, or --new-passphrase-env")
	errConflictingPassphraseSources   = errors.New("cannot use both --passphrase-stdin and --new-passphrase-stdin")
	errMissingNewPassphrase           = errors.New("no new passphrase provided (use --new-passphrase-file/--new-passphrase-cmd/--new-passphrase-stdin/--new-passphrase-env or run in a tty)")
	errEmptyNewPassphrase             = errors.New("empty new passphrase")
	errNewPassphraseMismatch          = errors.New("new passphrase confirmation does not match")
	errNewPassphraseUnchanged         = errors.New("new passphrase must differ from old passphrase")
	errConflictingBackupOptions       = errors.New("--no-backup cannot be used with --backup-dir")
)

type vaultResult struct {
	OK          bool   `json:"ok"`
	Action      string `json:"action"`
	ExitCode    int    `json:"exit_code"`
	Path        string `json:"path,omitempty"`
	Source      string `json:"source,omitempty"`
	Format      string `json:"format,omitempty"`
	KDF         string `json:"kdf,omitempty"`
	BackupPath  string `json:"backup_path,omitempty"`
	DryRun      bool   `json:"dry_run,omitempty"`
	WouldBackup bool   `json:"would_backup,omitempty"`
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
	cmd.AddCommand(newVaultRekeyCommand())
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

func newVaultRekeyCommand() *cobra.Command {
	var vaultPath string
	var passphraseCmd string
	var passphraseStdin bool
	var oldPassphraseFile string
	var newPassphraseCmd string
	var newPassphraseStdin bool
	var newPassphraseFile string
	var newPassphraseEnv string
	var backupDir string
	var noBackup bool
	var dryRun bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "rekey",
		Short: "Rotate the vault passphrase",
		RunE: func(cmd *cobra.Command, args []string) error {
			if strings.TrimSpace(vaultPath) == "" {
				p, err := defaultVaultPath()
				if err != nil {
					return vaultCommandError(cmd, jsonOut, err)
				}
				vaultPath = p
			}
			if noBackup && strings.TrimSpace(backupDir) != "" {
				return vaultCommandError(cmd, jsonOut, errConflictingBackupOptions)
			}
			if strings.TrimSpace(oldPassphraseFile) != "" && (passphraseStdin || strings.TrimSpace(passphraseCmd) != "") {
				return vaultCommandError(cmd, jsonOut, errConflictingOldPassphraseInputs)
			}
			if passphraseStdin && newPassphraseStdin {
				return vaultCommandError(cmd, jsonOut, errConflictingPassphraseSources)
			}

			oldPassphrase, err := resolveOldPassphraseForRekey(passphraseCmd, passphraseStdin, oldPassphraseFile)
			if err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(oldPassphrase)

			newPassphrase, err := resolveNewPassphraseForRekey(newPassphraseCmd, newPassphraseStdin, newPassphraseFile, newPassphraseEnv)
			if err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(newPassphrase)

			if bytes.Equal(oldPassphrase, newPassphrase) {
				return vaultCommandError(cmd, jsonOut, errNewPassphraseUnchanged)
			}

			wouldBackup, err := vaultRekeyPreflight(vaultPath, oldPassphrase, !noBackup, backupDir, dryRun)
			if err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}

			if dryRun {
				if jsonOut {
					return json.NewEncoder(cmd.OutOrStdout()).Encode(vaultResult{
						OK:          true,
						Action:      "vault_rekey",
						Path:        vaultPath,
						DryRun:      true,
						WouldBackup: wouldBackup,
					})
				}
				fmt.Fprintf(cmd.OutOrStdout(), "dry-run ok: vault can be rekeyed at %s\n", vaultPath)
				fmt.Fprintf(cmd.OutOrStdout(), "would-backup: %t\n", wouldBackup)
				return nil
			}

			backupPath := ""
			if wouldBackup {
				backupPath, err = createVaultBackup(vaultPath, backupDir)
				if err != nil {
					return vaultCommandError(cmd, jsonOut, err)
				}
			}
			if err := rekeyVaultAtomic(vaultPath, oldPassphrase, newPassphrase); err != nil {
				return vaultCommandError(cmd, jsonOut, err)
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(vaultResult{
					OK:         true,
					Action:     "vault_rekey",
					Path:       vaultPath,
					BackupPath: backupPath,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "rekeyed vault at %s\n", vaultPath)
			if backupPath != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "backup: %s\n", backupPath)
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
	cmd.Flags().BoolVar(&passphraseStdin, "passphrase-stdin", false, "read old passphrase from stdin (single line)")
	cmd.Flags().StringVar(&passphraseCmd, "passphrase-cmd", "", "execute command to obtain old passphrase (reads one line from stdout)")
	cmd.Flags().StringVar(&oldPassphraseFile, "old-passphrase-file", "", "read old passphrase from file (first line)")
	cmd.Flags().BoolVar(&newPassphraseStdin, "new-passphrase-stdin", false, "read new passphrase from stdin (single line)")
	cmd.Flags().StringVar(&newPassphraseCmd, "new-passphrase-cmd", "", "execute command to obtain new passphrase (reads one line from stdout)")
	cmd.Flags().StringVar(&newPassphraseFile, "new-passphrase-file", "", "read new passphrase from file (first line)")
	cmd.Flags().StringVar(&newPassphraseEnv, "new-passphrase-env", "", "read new passphrase from environment variable")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "run preflight checks without rewriting the vault")
	cmd.Flags().BoolVar(&noBackup, "no-backup", false, "skip creating backup before rekey")
	cmd.Flags().StringVar(&backupDir, "backup-dir", "", "backup directory (defaults to vault directory)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func resolveOldPassphraseForRekey(passphraseCmd string, passphraseStdin bool, oldPassphraseFile string) ([]byte, error) {
	if strings.TrimSpace(oldPassphraseFile) != "" {
		return readPassphraseFile(oldPassphraseFile)
	}
	return resolvePassphrase(passphraseCmd, passphraseStdin)
}

func resolveNewPassphraseForRekey(newPassphraseCmd string, newPassphraseStdin bool, newPassphraseFile, newPassphraseEnv string) ([]byte, error) {
	sourceCount := 0
	if strings.TrimSpace(newPassphraseCmd) != "" {
		sourceCount++
	}
	if newPassphraseStdin {
		sourceCount++
	}
	if strings.TrimSpace(newPassphraseFile) != "" {
		sourceCount++
	}
	if strings.TrimSpace(newPassphraseEnv) != "" {
		sourceCount++
	}
	if sourceCount > 1 {
		return nil, errConflictingNewPassphraseInputs
	}

	switch {
	case strings.TrimSpace(newPassphraseFile) != "":
		b, err := readPassphraseFile(newPassphraseFile)
		return b, normalizeNewPassphraseSourceError(err)
	case strings.TrimSpace(newPassphraseCmd) != "":
		args := strings.Fields(newPassphraseCmd)
		if len(args) == 0 {
			return nil, errors.New("empty --new-passphrase-cmd")
		}
		b, err := resolvePassphraseFromExec(args)
		return b, normalizeNewPassphraseSourceError(err)
	case newPassphraseStdin:
		b, err := readLine(os.Stdin)
		return b, normalizeNewPassphraseSourceError(err)
	case strings.TrimSpace(newPassphraseEnv) != "":
		val := os.Getenv(strings.TrimSpace(newPassphraseEnv))
		if strings.TrimSpace(val) == "" {
			return nil, fmt.Errorf("new passphrase environment variable %s is empty or unset", strings.TrimSpace(newPassphraseEnv))
		}
		return []byte(val), nil
	default:
		return promptNewPassphraseForRekey()
	}
}

func normalizeNewPassphraseSourceError(err error) error {
	if err == nil {
		return nil
	}
	if strings.Contains(strings.ToLower(err.Error()), "empty passphrase") {
		return errEmptyNewPassphrase
	}
	return err
}

func promptNewPassphraseForRekey() ([]byte, error) {
	if !term.IsTerminal(int(os.Stdin.Fd())) {
		return nil, errMissingNewPassphrase
	}

	fmt.Fprint(os.Stderr, "New passphrase: ")
	b, err := term.ReadPassword(int(os.Stdin.Fd()))
	fmt.Fprintln(os.Stderr)
	if err != nil {
		return nil, err
	}
	if len(b) == 0 {
		return nil, errEmptyNewPassphrase
	}

	fmt.Fprint(os.Stderr, "Confirm new passphrase: ")
	confirm, err := term.ReadPassword(int(os.Stdin.Fd()))
	fmt.Fprintln(os.Stderr)
	if err != nil {
		vault.Burn(b)
		return nil, err
	}
	defer vault.Burn(confirm)

	if !bytes.Equal(b, confirm) {
		vault.Burn(b)
		return nil, errNewPassphraseMismatch
	}
	return b, nil
}

func vaultRekeyPreflight(vaultPath string, oldPassphrase []byte, backupEnabled bool, backupDir string, dryRun bool) (bool, error) {
	if _, err := os.Stat(vaultPath); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, vault.ErrVaultNotFound
		}
		return false, err
	}
	v, err := vault.Open(vaultPath, oldPassphrase)
	if err != nil {
		return false, err
	}
	if err := v.Close(); err != nil {
		return false, err
	}
	if backupEnabled && dryRun {
		dir := strings.TrimSpace(backupDir)
		if dir == "" {
			dir = filepath.Dir(vaultPath)
		}
		st, err := os.Stat(dir)
		if err != nil {
			return false, fmt.Errorf("backup directory not found: %s", dir)
		}
		if !st.IsDir() {
			return false, fmt.Errorf("backup directory is not a directory: %s", dir)
		}
	}
	return backupEnabled, nil
}

func createVaultBackup(vaultPath, backupDir string) (string, error) {
	dir := strings.TrimSpace(backupDir)
	if dir == "" {
		dir = filepath.Dir(vaultPath)
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}

	var backupPath string
	suffix := time.Now().UnixNano()
	if dir == filepath.Dir(vaultPath) {
		backupPath = fmt.Sprintf("%s.bak.%d", vaultPath, suffix)
	} else {
		backupPath = filepath.Join(dir, fmt.Sprintf("%s.bak.%d", filepath.Base(vaultPath), suffix))
	}
	if err := copyFileForVaultRekey(vaultPath, backupPath, 0o600); err != nil {
		return "", err
	}
	return backupPath, nil
}

func rekeyVaultAtomic(vaultPath string, oldPassphrase, newPassphrase []byte) error {
	tmpPath := fmt.Sprintf("%s.rekey.tmp.%d", vaultPath, time.Now().UnixNano())
	if err := copyFileForVaultRekey(vaultPath, tmpPath, 0o600); err != nil {
		return err
	}
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(tmpPath)
		}
	}()

	if err := vault.Rekey(tmpPath, oldPassphrase, newPassphrase); err != nil {
		return err
	}
	if err := syncPath(tmpPath); err != nil {
		return err
	}
	if err := renameOver(tmpPath, vaultPath); err != nil {
		return err
	}
	cleanup = false
	_ = syncParentDir(vaultPath)
	return nil
}

func copyFileForVaultRekey(srcPath, dstPath string, mode os.FileMode) error {
	in, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer in.Close()

	out, err := os.OpenFile(dstPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	cleanup := true
	defer func() {
		if cleanup {
			_ = os.Remove(dstPath)
		}
	}()

	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		return err
	}
	if err := out.Sync(); err != nil {
		_ = out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	if err := os.Chmod(dstPath, mode); err != nil {
		return err
	}
	cleanup = false
	return nil
}

func syncPath(path string) error {
	f, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	return f.Sync()
}

func syncParentDir(path string) error {
	dir := filepath.Dir(path)
	f, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer f.Close()
	if err := f.Sync(); err != nil {
		return nil
	}
	return nil
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
	case errors.Is(err, errConflictingBackupOptions):
		return reasonConflictingBackupOptions
	case errors.Is(err, errConflictingOldPassphraseInputs), errors.Is(err, errConflictingNewPassphraseInputs), errors.Is(err, errConflictingPassphraseSources):
		return reasonConflictingPassphraseSources
	case errors.Is(err, errMissingNewPassphrase):
		return reasonMissingNewPassphrase
	case errors.Is(err, errEmptyNewPassphrase):
		return reasonEmptyNewPassphrase
	case errors.Is(err, errNewPassphraseMismatch):
		return reasonNewPassphraseMismatch
	case errors.Is(err, errNewPassphraseUnchanged):
		return reasonNewPassphraseUnchanged
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
	case strings.Contains(msg, "empty --new-passphrase-cmd"):
		return reasonEmptyPassphraseCommand
	case strings.Contains(msg, "new passphrase environment variable"):
		return reasonMissingNewPassphrase
	default:
		return reasonVaultFailed
	}
}
