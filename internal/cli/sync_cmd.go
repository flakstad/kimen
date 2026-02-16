package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"kimen/internal/bundle"
	"kimen/internal/exitcode"
)

type syncResult struct {
	OK          bool   `json:"ok"`
	Action      string `json:"action"`
	Remote      string `json:"remote"`
	RemoteType  string `json:"remote_type,omitempty"`
	RemotePath  string `json:"remote_path,omitempty"`
	BundlePath  string `json:"bundle_path,omitempty"`
	VaultPath   string `json:"vault_path,omitempty"`
	RemoteRev   string `json:"remote_rev,omitempty"`
	LastSeenRev string `json:"last_seen_rev,omitempty"`
	BackupPath  string `json:"backup_path,omitempty"`
	HasRemote   bool   `json:"has_remote"`
	HasLocal    bool   `json:"has_local,omitempty"`
	InSync      bool   `json:"in_sync,omitempty"`
	CanPush     bool   `json:"can_push,omitempty"`
	NeedsPull   bool   `json:"needs_pull,omitempty"`
}

type syncErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
}

type syncConflictResult struct {
	OK          bool   `json:"ok"`
	Action      string `json:"action"`
	Remote      string `json:"remote"`
	RemoteType  string `json:"remote_type,omitempty"`
	RemotePath  string `json:"remote_path,omitempty"`
	BundlePath  string `json:"bundle_path,omitempty"`
	RemoteRev   string `json:"remote_rev,omitempty"`
	LastSeenRev string `json:"last_seen_rev,omitempty"`
	HasRemote   bool   `json:"has_remote"`
	HasConflict bool   `json:"has_conflict"`
	Reason      string `json:"reason,omitempty"`
	ExpectedRev string `json:"expected_rev,omitempty"`
	ActualRev   string `json:"actual_rev,omitempty"`
	Message     string `json:"message,omitempty"`
}

type syncResetBaselineResult struct {
	OK          bool   `json:"ok"`
	Action      string `json:"action"`
	Remote      string `json:"remote"`
	Mode        string `json:"mode"`
	PreviousRev string `json:"previous_rev,omitempty"`
	NewRev      string `json:"new_rev,omitempty"`
}

type syncRestoreResult struct {
	OK                bool   `json:"ok"`
	Action            string `json:"action"`
	VaultPath         string `json:"vault_path"`
	SourceBackupPath  string `json:"source_backup_path"`
	CurrentBackupPath string `json:"current_backup_path,omitempty"`
}

func newSyncCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "sync",
		Short: "Sync vault bundles with configured remotes",
	}
	cmd.AddCommand(newSyncStatusCommand())
	cmd.AddCommand(newSyncConflictsCommand())
	cmd.AddCommand(newSyncResetBaselineCommand())
	cmd.AddCommand(newSyncRestoreCommand())
	cmd.AddCommand(newSyncPushCommand())
	cmd.AddCommand(newSyncPullCommand())
	return cmd
}

func newSyncStatusCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "status",
		Short: "Show local sync state against a remote",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if remote.Type != "fs" {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q", remote.Type))
			}

			remoteRev, hasRemote, bundlePath, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			vaultPath, err := defaultVaultPath()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			_, hasLocal, err := localVaultRevision(vaultPath)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			lastSeen := ""
			if c.Sync != nil {
				lastSeen = c.Sync[remote.Name].LastSeenRev
			}
			inSync := lastSeen != "" && hasRemote && lastSeen == remoteRev
			canPush := false
			needsPull := false
			switch {
			case !hasRemote && lastSeen == "":
				canPush = true
			case !hasRemote && lastSeen != "":
				canPush = false
			case hasRemote && lastSeen == "":
				needsPull = true
			case hasRemote && lastSeen != remoteRev:
				needsPull = true
			case hasRemote && lastSeen == remoteRev:
				canPush = true
			}

			res := syncResult{
				OK:          true,
				Action:      "sync_status",
				Remote:      remote.Name,
				RemoteType:  remote.Type,
				RemotePath:  remote.Path,
				BundlePath:  bundlePath,
				VaultPath:   vaultPath,
				RemoteRev:   remoteRev,
				LastSeenRev: lastSeen,
				HasRemote:   hasRemote,
				HasLocal:    hasLocal,
				InSync:      inSync,
				CanPush:     canPush,
				NeedsPull:   needsPull,
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(res)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "remote: %s (%s)\n", remote.Name, remote.Type)
			fmt.Fprintf(cmd.OutOrStdout(), "path: %s\n", remote.Path)
			if hasRemote {
				fmt.Fprintf(cmd.OutOrStdout(), "remote-rev: %s\n", remoteRev)
			} else {
				fmt.Fprintln(cmd.OutOrStdout(), "remote-rev: (missing)")
			}
			if strings.TrimSpace(lastSeen) == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "last-seen-rev: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "last-seen-rev: %s\n", lastSeen)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "in-sync: %t\n", inSync)
			fmt.Fprintf(cmd.OutOrStdout(), "can-push: %t\n", canPush)
			fmt.Fprintf(cmd.OutOrStdout(), "needs-pull: %t\n", needsPull)
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSyncConflictsCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "conflicts",
		Short: "Inspect whether push is currently blocked by a sync conflict",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if remote.Type != "fs" {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q", remote.Type))
			}

			remoteRev, hasRemote, bundlePath, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lastSeen := ""
			if c.Sync != nil {
				lastSeen = strings.TrimSpace(c.Sync[remote.Name].LastSeenRev)
			}
			details := detectSyncConflict(lastSeen, remoteRev, hasRemote)
			res := syncConflictResult{
				OK:          true,
				Action:      "sync_conflicts",
				Remote:      remote.Name,
				RemoteType:  remote.Type,
				RemotePath:  remote.Path,
				BundlePath:  bundlePath,
				RemoteRev:   remoteRev,
				LastSeenRev: lastSeen,
				HasRemote:   hasRemote,
				HasConflict: details.HasConflict,
				Reason:      details.Reason,
				ExpectedRev: details.ExpectedRev,
				ActualRev:   details.ActualRev,
				Message:     details.Message,
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(res)
			}
			if !details.HasConflict {
				fmt.Fprintf(cmd.OutOrStdout(), "no conflict for remote %s\n", remote.Name)
				return nil
			}
			fmt.Fprintf(cmd.OutOrStdout(), "conflict: %s\n", details.Reason)
			fmt.Fprintf(cmd.OutOrStdout(), "message: %s\n", details.Message)
			if details.ExpectedRev == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "expected-rev: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "expected-rev: %s\n", details.ExpectedRev)
			}
			if details.ActualRev == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "actual-rev: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "actual-rev: %s\n", details.ActualRev)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "next: kimen sync pull --remote %s\n", remote.Name)
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSyncResetBaselineCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var toRemote bool
	var clear bool
	var rev string
	var yes bool
	cmd := &cobra.Command{
		Use:   "reset-baseline",
		Short: "Dangerous: manually override or clear a remote sync baseline",
		RunE: func(cmd *cobra.Command, args []string) error {
			modeCount := 0
			if toRemote {
				modeCount++
			}
			if clear {
				modeCount++
			}
			rev = strings.TrimSpace(rev)
			if rev != "" {
				modeCount++
			}
			if modeCount != 1 {
				return syncCommandError(cmd, jsonOut, errors.New("choose exactly one mode: --to-remote, --clear, or --rev <sha256>"))
			}
			if !yes {
				return syncCommandError(cmd, jsonOut, errors.New("refusing to reset baseline without --yes"))
			}

			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if remote.Type != "fs" {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q", remote.Type))
			}

			oldRev := ""
			if c.Sync != nil {
				oldRev = strings.TrimSpace(c.Sync[remote.Name].LastSeenRev)
			}
			mode := ""
			newRev := ""
			switch {
			case clear:
				mode = "clear"
			case toRemote:
				mode = "to_remote"
				remoteRev, hasRemote, _, err := remoteRevision(remote)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				if !hasRemote {
					return syncCommandError(cmd, jsonOut, errors.New("remote bundle is missing; cannot set baseline to remote"))
				}
				newRev = remoteRev
			default:
				mode = "rev"
				newRev = rev
			}

			if clear {
				if c.Sync != nil {
					delete(c.Sync, remote.Name)
					if len(c.Sync) == 0 {
						c.Sync = nil
					}
				}
			} else {
				if c.Sync == nil {
					c.Sync = make(map[string]syncConfig)
				}
				c.Sync[remote.Name] = syncConfig{LastSeenRev: newRev}
			}
			if _, err := saveConfig(c); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(syncResetBaselineResult{
					OK:          true,
					Action:      "sync_reset_baseline",
					Remote:      remote.Name,
					Mode:        mode,
					PreviousRev: oldRev,
					NewRev:      newRev,
				})
			}
			if newRev == "" {
				fmt.Fprintf(cmd.OutOrStdout(), "baseline cleared for remote %s\n", remote.Name)
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "baseline reset for remote %s: %s\n", remote.Name, newRev)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&toRemote, "to-remote", false, "set baseline to the current remote revision")
	cmd.Flags().BoolVar(&clear, "clear", false, "clear the stored baseline")
	cmd.Flags().StringVar(&rev, "rev", "", "set baseline to an explicit revision")
	cmd.Flags().BoolVar(&yes, "yes", false, "confirm that you want to bypass normal conflict checks")
	return cmd
}

func newSyncRestoreCommand() *cobra.Command {
	var backupPath string
	var vaultPath string
	var noBackup bool
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "restore",
		Short: "Restore local vault content from a backup file",
		RunE: func(cmd *cobra.Command, args []string) error {
			backupPath = strings.TrimSpace(backupPath)
			if backupPath == "" {
				return syncCommandError(cmd, jsonOut, errors.New("--backup is required"))
			}
			if _, err := os.Stat(backupPath); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			var err error
			if strings.TrimSpace(vaultPath) == "" {
				vaultPath, err = defaultVaultPath()
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
			}
			if err := os.MkdirAll(filepath.Dir(vaultPath), 0o700); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			currentBackup := ""
			if !noBackup {
				if _, err := os.Stat(vaultPath); err == nil {
					currentBackup, err = backupExistingVault(vaultPath)
					if err != nil {
						return syncCommandError(cmd, jsonOut, err)
					}
				} else if !errors.Is(err, os.ErrNotExist) {
					return syncCommandError(cmd, jsonOut, err)
				}
			}
			if err := copyFileAtomic(backupPath, vaultPath, 0o600); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(syncRestoreResult{
					OK:                true,
					Action:            "sync_restore",
					VaultPath:         vaultPath,
					SourceBackupPath:  backupPath,
					CurrentBackupPath: currentBackup,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "restored vault from %s -> %s\n", backupPath, vaultPath)
			if currentBackup != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "backup: %s\n", currentBackup)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&backupPath, "backup", "", "backup vault path to restore from")
	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path to restore into (defaults to $KIMEN_VAULT or user config dir)")
	cmd.Flags().BoolVar(&noBackup, "no-backup", false, "skip creating a backup of the current vault before restore")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSyncPushCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	cmd := &cobra.Command{
		Use:   "push",
		Short: "Push local vault to remote as encrypted bundle",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if remote.Type != "fs" {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q", remote.Type))
			}
			if strings.TrimSpace(remote.Recipient) == "" {
				return syncCommandError(cmd, jsonOut, errors.New("remote recipient is not configured (set --recipient on `remote add`)"))
			}

			vaultPath, err := defaultVaultPath()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if _, err := os.Stat(vaultPath); err != nil {
				if errors.Is(err, os.ErrNotExist) {
					return syncCommandError(cmd, jsonOut, errors.New("local vault file not found"))
				}
				return syncCommandError(cmd, jsonOut, err)
			}

			remoteRev, hasRemote, bundlePath, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lastSeen := ""
			if c.Sync != nil {
				lastSeen = strings.TrimSpace(c.Sync[remote.Name].LastSeenRev)
			}
			if err := validatePushBaseline(lastSeen, remoteRev, hasRemote); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			if err := sealVaultToRemoteAtomic(vaultPath, bundlePath, remote.Recipient); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			newRev, _, err := fileRevision(bundlePath)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if c.Sync == nil {
				c.Sync = make(map[string]syncConfig)
			}
			c.Sync[remote.Name] = syncConfig{LastSeenRev: newRev}
			if _, err := saveConfig(c); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(syncResult{
					OK:         true,
					Action:     "sync_push",
					Remote:     remote.Name,
					RemoteType: remote.Type,
					RemotePath: remote.Path,
					BundlePath: bundlePath,
					RemoteRev:  newRev,
					HasRemote:  true,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "pushed %s (rev=%s)\n", remote.Name, newRev)
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSyncPullCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var noBackup bool
	cmd := &cobra.Command{
		Use:   "pull",
		Short: "Pull remote encrypted bundle into local vault",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if remote.Type != "fs" {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q", remote.Type))
			}
			if strings.TrimSpace(remote.Identity) == "" {
				return syncCommandError(cmd, jsonOut, errors.New("remote identity is not configured (set --identity on `remote add`)"))
			}

			remoteRev, hasRemote, bundlePath, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if !hasRemote {
				return syncCommandError(cmd, jsonOut, errors.New("remote bundle is missing"))
			}

			vaultPath, err := defaultVaultPath()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if err := os.MkdirAll(filepath.Dir(vaultPath), 0o700); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			backupPath := ""
			if !noBackup {
				if _, err := os.Stat(vaultPath); err == nil {
					backupPath, err = backupExistingVault(vaultPath)
					if err != nil {
						return syncCommandError(cmd, jsonOut, err)
					}
				} else if !errors.Is(err, os.ErrNotExist) {
					return syncCommandError(cmd, jsonOut, err)
				}
			}

			id, err := bundle.LoadIdentity(remote.Identity, false, nil)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if err := bundle.OpenToVaultFile(bundlePath, vaultPath, id, true); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			if c.Sync == nil {
				c.Sync = make(map[string]syncConfig)
			}
			c.Sync[remote.Name] = syncConfig{LastSeenRev: remoteRev}
			if _, err := saveConfig(c); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(syncResult{
					OK:          true,
					Action:      "sync_pull",
					Remote:      remote.Name,
					RemoteType:  remote.Type,
					RemotePath:  remote.Path,
					BundlePath:  bundlePath,
					VaultPath:   vaultPath,
					RemoteRev:   remoteRev,
					LastSeenRev: remoteRev,
					BackupPath:  backupPath,
					HasRemote:   true,
					HasLocal:    true,
					InSync:      true,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "pulled %s (rev=%s)\n", remote.Name, remoteRev)
			if backupPath != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "backup: %s\n", backupPath)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&noBackup, "no-backup", false, "skip creating a local vault backup before overwrite")
	return cmd
}

func validatePushBaseline(lastSeen, remoteRev string, hasRemote bool) error {
	details := detectSyncConflict(lastSeen, remoteRev, hasRemote)
	if !details.HasConflict {
		return nil
	}
	return fmt.Errorf("%w: %s", errSyncConflict, details.Message)
}

func sealVaultToRemoteAtomic(vaultPath, bundlePath, recipient string) error {
	if err := os.MkdirAll(filepath.Dir(bundlePath), 0o700); err != nil {
		return err
	}
	tmp := bundlePath + ".tmp"
	if err := bundle.SealVaultFile(vaultPath, tmp, []string{recipient}); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Chmod(tmp, 0o600); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return renameOver(tmp, bundlePath)
}

func syncCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	code := syncExitCode(err)
	if jsonOut {
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(syncErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: code,
		})
	} else {
		fmt.Fprintln(cmd.ErrOrStderr(), err.Error())
	}
	return exitcode.New(code, err)
}

func syncExitCode(err error) int {
	if errors.Is(err, errSyncConflict) {
		return exitcode.CodeSyncConflict
	}
	return exitcode.CodeSyncFailed
}

func backupExistingVault(vaultPath string) (string, error) {
	in, err := os.Open(vaultPath)
	if err != nil {
		return "", err
	}
	defer in.Close()

	backupPath := fmt.Sprintf("%s.bak.%d", vaultPath, time.Now().UnixNano())
	out, err := os.OpenFile(backupPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return "", err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		_ = os.Remove(backupPath)
		return "", err
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(backupPath)
		return "", err
	}
	if err := os.Chmod(backupPath, 0o600); err != nil {
		_ = os.Remove(backupPath)
		return "", err
	}
	return backupPath, nil
}

func copyFileAtomic(srcPath, dstPath string, mode os.FileMode) error {
	in, err := os.Open(srcPath)
	if err != nil {
		return err
	}
	defer in.Close()

	tmp := fmt.Sprintf("%s.tmp.%d", dstPath, time.Now().UnixNano())
	out, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		_ = out.Close()
		_ = os.Remove(tmp)
		return err
	}
	if err := out.Close(); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := os.Chmod(tmp, mode); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := renameOver(tmp, dstPath); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	return nil
}
