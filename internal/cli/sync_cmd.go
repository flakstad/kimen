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
	OK              bool   `json:"ok"`
	Action          string `json:"action"`
	Remote          string `json:"remote"`
	RemoteType      string `json:"remote_type,omitempty"`
	RemotePath      string `json:"remote_path,omitempty"`
	BundlePath      string `json:"bundle_path,omitempty"`
	VaultPath       string `json:"vault_path,omitempty"`
	RemoteRev       string `json:"remote_rev,omitempty"`
	LastSeenRev     string `json:"last_seen_rev,omitempty"`
	BackupPath      string `json:"backup_path,omitempty"`
	HasRemote       bool   `json:"has_remote"`
	HasLock         bool   `json:"has_lock"`
	HasLocal        bool   `json:"has_local,omitempty"`
	InSync          bool   `json:"in_sync,omitempty"`
	CanPush         bool   `json:"can_push,omitempty"`
	NeedsPull       bool   `json:"needs_pull,omitempty"`
	LockPath        string `json:"lock_path,omitempty"`
	LockAge         string `json:"lock_age,omitempty"`
	LockPID         string `json:"lock_pid,omitempty"`
	LockHost        string `json:"lock_host,omitempty"`
	LockUser        string `json:"lock_user,omitempty"`
	LockCreated     string `json:"lock_created,omitempty"`
	LockError       string `json:"lock_error,omitempty"`
	StaleLockBroken bool   `json:"stale_lock_broken,omitempty"`
	LockBlocksPush  bool   `json:"lock_blocks_push,omitempty"`
	LikelyStale     bool   `json:"likely_stale,omitempty"`
	LockAgeSeconds  int64  `json:"lock_age_seconds,omitempty"`
}

type syncErrorResult struct {
	OK       bool   `json:"ok"`
	Error    string `json:"error"`
	ExitCode int    `json:"exit_code"`
}

type syncConflictResult struct {
	OK             bool   `json:"ok"`
	Action         string `json:"action"`
	Remote         string `json:"remote"`
	RemoteType     string `json:"remote_type,omitempty"`
	RemotePath     string `json:"remote_path,omitempty"`
	BundlePath     string `json:"bundle_path,omitempty"`
	RemoteRev      string `json:"remote_rev,omitempty"`
	LastSeenRev    string `json:"last_seen_rev,omitempty"`
	HasRemote      bool   `json:"has_remote"`
	HasLock        bool   `json:"has_lock"`
	HasConflict    bool   `json:"has_conflict"`
	Reason         string `json:"reason,omitempty"`
	ExpectedRev    string `json:"expected_rev,omitempty"`
	ActualRev      string `json:"actual_rev,omitempty"`
	Message        string `json:"message,omitempty"`
	LockPath       string `json:"lock_path,omitempty"`
	LockAge        string `json:"lock_age,omitempty"`
	LockPID        string `json:"lock_pid,omitempty"`
	LockHost       string `json:"lock_host,omitempty"`
	LockUser       string `json:"lock_user,omitempty"`
	LockCreated    string `json:"lock_created,omitempty"`
	LockError      string `json:"lock_error,omitempty"`
	LockBlocksPush bool   `json:"lock_blocks_push,omitempty"`
	LikelyStale    bool   `json:"likely_stale,omitempty"`
	LockAgeSeconds int64  `json:"lock_age_seconds,omitempty"`
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

type syncUnlockResult struct {
	OK        bool   `json:"ok"`
	Action    string `json:"action"`
	Remote    string `json:"remote"`
	LockPath  string `json:"lock_path"`
	Removed   bool   `json:"removed"`
	LockAge   string `json:"lock_age,omitempty"`
	Reason    string `json:"reason,omitempty"`
	Confirmed bool   `json:"confirmed,omitempty"`
}

type pushLockInfo struct {
	HasLock     bool
	LockPath    string
	LockAge     string
	LockAgeDur  time.Duration
	LockPID     string
	LockHost    string
	LockUser    string
	LockCreated string
	LockError   string
}

func newSyncCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "sync",
		Short: "Sync vault bundles with configured remotes",
	}
	cmd.AddCommand(newSyncStatusCommand())
	cmd.AddCommand(newSyncConflictsCommand())
	cmd.AddCommand(newSyncResetBaselineCommand())
	cmd.AddCommand(newSyncUnlockCommand())
	cmd.AddCommand(newSyncRestoreCommand())
	cmd.AddCommand(newSyncPushCommand())
	cmd.AddCommand(newSyncPullCommand())
	return cmd
}

func newSyncStatusCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var staleThreshold time.Duration
	cmd := &cobra.Command{
		Use:   "status",
		Short: "Show local sync state against a remote",
		RunE: func(cmd *cobra.Command, args []string) error {
			if staleThreshold < 0 {
				return syncCommandError(cmd, jsonOut, errors.New("--stale-threshold must be >= 0"))
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

			remoteRev, hasRemote, bundlePath, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lockInfo, err := readRemotePushLockInfo(bundlePath)
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
			lockBlocksPush := lockInfo.HasLock
			if lockBlocksPush {
				canPush = false
			}
			likelyStale := false
			if lockInfo.HasLock && staleThreshold > 0 && lockInfo.LockAgeDur >= staleThreshold {
				likelyStale = true
			}

			res := syncResult{
				OK:             true,
				Action:         "sync_status",
				Remote:         remote.Name,
				RemoteType:     remote.Type,
				RemotePath:     remote.Path,
				BundlePath:     bundlePath,
				VaultPath:      vaultPath,
				RemoteRev:      remoteRev,
				LastSeenRev:    lastSeen,
				HasRemote:      hasRemote,
				HasLock:        lockInfo.HasLock,
				HasLocal:       hasLocal,
				InSync:         inSync,
				CanPush:        canPush,
				NeedsPull:      needsPull,
				LockPath:       lockInfo.LockPath,
				LockAge:        lockInfo.LockAge,
				LockPID:        lockInfo.LockPID,
				LockHost:       lockInfo.LockHost,
				LockUser:       lockInfo.LockUser,
				LockCreated:    lockInfo.LockCreated,
				LockError:      lockInfo.LockError,
				LockBlocksPush: lockBlocksPush,
				LikelyStale:    likelyStale,
				LockAgeSeconds: int64(lockInfo.LockAgeDur.Seconds()),
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
			fmt.Fprintf(cmd.OutOrStdout(), "lock-blocks-push: %t\n", lockBlocksPush)
			if lockInfo.HasLock && staleThreshold > 0 {
				fmt.Fprintf(cmd.OutOrStdout(), "likely-stale: %t (threshold=%s)\n", likelyStale, staleThreshold)
			}
			if lockInfo.HasLock {
				fmt.Fprintf(cmd.OutOrStdout(), "push-lock: present (%s)\n", lockInfo.LockPath)
				if lockInfo.LockAge != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-age: %s\n", lockInfo.LockAge)
				}
				if lockInfo.LockPID != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-pid: %s\n", lockInfo.LockPID)
				}
				if lockInfo.LockHost != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-host: %s\n", lockInfo.LockHost)
				}
				if lockInfo.LockUser != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-user: %s\n", lockInfo.LockUser)
				}
				if lockInfo.LockCreated != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-created: %s\n", lockInfo.LockCreated)
				}
			} else {
				fmt.Fprintln(cmd.OutOrStdout(), "push-lock: (none)")
			}
			if lockInfo.LockError != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "push-lock-error: %s\n", lockInfo.LockError)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().DurationVar(&staleThreshold, "stale-threshold", 0, "mark lock as likely stale when lock age is >= this duration")
	return cmd
}

func newSyncConflictsCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var staleThreshold time.Duration
	cmd := &cobra.Command{
		Use:   "conflicts",
		Short: "Inspect whether push is currently blocked by a sync conflict",
		RunE: func(cmd *cobra.Command, args []string) error {
			if staleThreshold < 0 {
				return syncCommandError(cmd, jsonOut, errors.New("--stale-threshold must be >= 0"))
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

			remoteRev, hasRemote, bundlePath, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lockInfo, err := readRemotePushLockInfo(bundlePath)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lastSeen := ""
			if c.Sync != nil {
				lastSeen = strings.TrimSpace(c.Sync[remote.Name].LastSeenRev)
			}
			details := detectSyncConflict(lastSeen, remoteRev, hasRemote)
			lockBlocksPush := lockInfo.HasLock
			likelyStale := false
			if lockInfo.HasLock && staleThreshold > 0 && lockInfo.LockAgeDur >= staleThreshold {
				likelyStale = true
			}
			res := syncConflictResult{
				OK:             true,
				Action:         "sync_conflicts",
				Remote:         remote.Name,
				RemoteType:     remote.Type,
				RemotePath:     remote.Path,
				BundlePath:     bundlePath,
				RemoteRev:      remoteRev,
				LastSeenRev:    lastSeen,
				HasRemote:      hasRemote,
				HasLock:        lockInfo.HasLock,
				HasConflict:    details.HasConflict,
				Reason:         details.Reason,
				ExpectedRev:    details.ExpectedRev,
				ActualRev:      details.ActualRev,
				Message:        details.Message,
				LockPath:       lockInfo.LockPath,
				LockAge:        lockInfo.LockAge,
				LockPID:        lockInfo.LockPID,
				LockHost:       lockInfo.LockHost,
				LockUser:       lockInfo.LockUser,
				LockCreated:    lockInfo.LockCreated,
				LockError:      lockInfo.LockError,
				LockBlocksPush: lockBlocksPush,
				LikelyStale:    likelyStale,
				LockAgeSeconds: int64(lockInfo.LockAgeDur.Seconds()),
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(res)
			}
			if !details.HasConflict {
				fmt.Fprintf(cmd.OutOrStdout(), "no conflict for remote %s\n", remote.Name)
				if lockInfo.HasLock {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock: present (%s)\n", lockInfo.LockPath)
					if lockInfo.LockAge != "" {
						fmt.Fprintf(cmd.OutOrStdout(), "push-lock-age: %s\n", lockInfo.LockAge)
					}
					if lockInfo.LockPID != "" {
						fmt.Fprintf(cmd.OutOrStdout(), "push-lock-pid: %s\n", lockInfo.LockPID)
					}
					if lockInfo.LockHost != "" {
						fmt.Fprintf(cmd.OutOrStdout(), "push-lock-host: %s\n", lockInfo.LockHost)
					}
					if lockInfo.LockUser != "" {
						fmt.Fprintf(cmd.OutOrStdout(), "push-lock-user: %s\n", lockInfo.LockUser)
					}
				} else {
					fmt.Fprintln(cmd.OutOrStdout(), "push-lock: (none)")
				}
				fmt.Fprintf(cmd.OutOrStdout(), "lock-blocks-push: %t\n", lockBlocksPush)
				if lockInfo.HasLock && staleThreshold > 0 {
					fmt.Fprintf(cmd.OutOrStdout(), "likely-stale: %t (threshold=%s)\n", likelyStale, staleThreshold)
				}
				if lockInfo.LockError != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-error: %s\n", lockInfo.LockError)
				}
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
			if lockInfo.HasLock {
				fmt.Fprintf(cmd.OutOrStdout(), "push-lock: present (%s)\n", lockInfo.LockPath)
				if lockInfo.LockAge != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-age: %s\n", lockInfo.LockAge)
				}
				if lockInfo.LockPID != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-pid: %s\n", lockInfo.LockPID)
				}
				if lockInfo.LockHost != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-host: %s\n", lockInfo.LockHost)
				}
				if lockInfo.LockUser != "" {
					fmt.Fprintf(cmd.OutOrStdout(), "push-lock-user: %s\n", lockInfo.LockUser)
				}
			} else {
				fmt.Fprintln(cmd.OutOrStdout(), "push-lock: (none)")
			}
			fmt.Fprintf(cmd.OutOrStdout(), "lock-blocks-push: %t\n", lockBlocksPush)
			if lockInfo.HasLock && staleThreshold > 0 {
				fmt.Fprintf(cmd.OutOrStdout(), "likely-stale: %t (threshold=%s)\n", likelyStale, staleThreshold)
			}
			if lockInfo.LockError != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "push-lock-error: %s\n", lockInfo.LockError)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().DurationVar(&staleThreshold, "stale-threshold", 0, "mark lock as likely stale when lock age is >= this duration")
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

func newSyncUnlockCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var yes bool
	var ifOlderThan time.Duration
	cmd := &cobra.Command{
		Use:   "unlock",
		Short: "Remove a remote push lock file (emergency use)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if ifOlderThan < 0 {
				return syncCommandError(cmd, jsonOut, errors.New("--if-older-than must be >= 0"))
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
			bundlePath, err := remoteBundlePath(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lockPath := remotePushLockPath(bundlePath)
			st, err := os.Stat(lockPath)
			if err != nil {
				if errors.Is(err, os.ErrNotExist) {
					if jsonOut {
						return json.NewEncoder(cmd.OutOrStdout()).Encode(syncUnlockResult{
							OK:       true,
							Action:   "sync_unlock",
							Remote:   remote.Name,
							LockPath: lockPath,
							Removed:  false,
							Reason:   "lock_missing",
						})
					}
					fmt.Fprintf(cmd.OutOrStdout(), "no lock file found for remote %s\n", remote.Name)
					return nil
				}
				return syncCommandError(cmd, jsonOut, err)
			}
			lockAge := time.Since(st.ModTime())
			if ifOlderThan > 0 && lockAge < ifOlderThan {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("refusing to unlock %s: lock is only %s old (requires >= %s)", lockPath, lockAge.Truncate(time.Second), ifOlderThan))
			}
			if !yes {
				return syncCommandError(cmd, jsonOut, errors.New("refusing to remove lock without --yes"))
			}
			if err := os.Remove(lockPath); err != nil {
				if errors.Is(err, os.ErrNotExist) {
					if jsonOut {
						return json.NewEncoder(cmd.OutOrStdout()).Encode(syncUnlockResult{
							OK:       true,
							Action:   "sync_unlock",
							Remote:   remote.Name,
							LockPath: lockPath,
							Removed:  false,
							Reason:   "lock_missing",
						})
					}
					fmt.Fprintf(cmd.OutOrStdout(), "no lock file found for remote %s\n", remote.Name)
					return nil
				}
				return syncCommandError(cmd, jsonOut, err)
			}
			ageStr := lockAge.Truncate(time.Second).String()
			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(syncUnlockResult{
					OK:        true,
					Action:    "sync_unlock",
					Remote:    remote.Name,
					LockPath:  lockPath,
					Removed:   true,
					LockAge:   ageStr,
					Confirmed: true,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "removed lock for remote %s: %s (age=%s)\n", remote.Name, lockPath, ageStr)
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&yes, "yes", false, "confirm that you want to remove the lock file")
	cmd.Flags().DurationVar(&ifOlderThan, "if-older-than", 0, "only unlock if lock file is at least this old (e.g. 5m)")
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
	var lockWait time.Duration
	var breakStaleLockAfter time.Duration
	cmd := &cobra.Command{
		Use:   "push",
		Short: "Push local vault to remote as encrypted bundle",
		RunE: func(cmd *cobra.Command, args []string) error {
			if lockWait < 0 {
				return syncCommandError(cmd, jsonOut, errors.New("--lock-wait must be >= 0"))
			}
			if breakStaleLockAfter < 0 {
				return syncCommandError(cmd, jsonOut, errors.New("--break-stale-lock-after must be >= 0"))
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

			bundlePath, err := remoteBundlePath(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			releaseLock, staleLockBroken, err := acquireRemotePushLock(bundlePath, lockWait, breakStaleLockAfter)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			defer releaseLock()

			remoteRev, hasRemote, _, err := remoteRevision(remote)
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
					OK:              true,
					Action:          "sync_push",
					Remote:          remote.Name,
					RemoteType:      remote.Type,
					RemotePath:      remote.Path,
					BundlePath:      bundlePath,
					RemoteRev:       newRev,
					HasRemote:       true,
					StaleLockBroken: staleLockBroken,
				})
			}
			fmt.Fprintf(cmd.OutOrStdout(), "pushed %s (rev=%s)\n", remote.Name, newRev)
			if staleLockBroken {
				fmt.Fprintln(cmd.OutOrStdout(), "warning: broke stale remote push lock")
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().DurationVar(&lockWait, "lock-wait", 0, "how long to wait for remote push lock (e.g. 10s, 1m); default is fail-fast")
	cmd.Flags().DurationVar(&breakStaleLockAfter, "break-stale-lock-after", 0, "break remote push lock when it is older than this duration")
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

func acquireRemotePushLock(bundlePath string, wait time.Duration, breakStaleAfter time.Duration) (func(), bool, error) {
	lockPath := remotePushLockPath(bundlePath)
	if err := os.MkdirAll(filepath.Dir(lockPath), 0o700); err != nil {
		return nil, false, err
	}
	if wait < 0 {
		wait = 0
	}
	if breakStaleAfter < 0 {
		breakStaleAfter = 0
	}
	deadline := time.Now().Add(wait)
	brokeStaleLock := false

	for {
		f, err := os.OpenFile(lockPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
		if err == nil {
			host, _ := os.Hostname()
			user := os.Getenv("USER")
			if strings.TrimSpace(user) == "" {
				user = os.Getenv("USERNAME")
			}
			_, _ = fmt.Fprintf(
				f,
				"pid=%d\ncreated_at=%s\nhost=%s\nuser=%s\n",
				os.Getpid(),
				time.Now().UTC().Format(time.RFC3339Nano),
				host,
				user,
			)
			if closeErr := f.Close(); closeErr != nil {
				_ = os.Remove(lockPath)
				return nil, false, closeErr
			}
			return func() { _ = os.Remove(lockPath) }, brokeStaleLock, nil
		}
		if !errors.Is(err, os.ErrExist) {
			return nil, false, err
		}
		if breakStaleAfter > 0 {
			info, infoErr := readRemotePushLockInfo(bundlePath)
			if infoErr != nil {
				return nil, false, infoErr
			}
			if info.HasLock && info.LockAgeDur >= breakStaleAfter {
				if rmErr := os.Remove(lockPath); rmErr != nil && !errors.Is(rmErr, os.ErrNotExist) {
					return nil, false, rmErr
				}
				brokeStaleLock = true
				continue
			}
		}
		if wait == 0 || !time.Now().Before(deadline) {
			return nil, false, fmt.Errorf("remote push lock exists: %s (another sync push may be in progress; use --lock-wait to wait)", lockPath)
		}
		sleep := 250 * time.Millisecond
		remaining := time.Until(deadline)
		if remaining < sleep {
			sleep = remaining
		}
		if sleep <= 0 {
			return nil, false, fmt.Errorf("remote push lock exists: %s (another sync push may be in progress)", lockPath)
		}
		time.Sleep(sleep)
	}
}

func remotePushLockPath(bundlePath string) string {
	return bundlePath + ".lock"
}

func readRemotePushLockInfo(bundlePath string) (pushLockInfo, error) {
	lockPath := remotePushLockPath(bundlePath)
	info := pushLockInfo{LockPath: lockPath}
	st, err := os.Stat(lockPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return info, nil
		}
		return info, err
	}
	info.HasLock = true
	info.LockAgeDur = time.Since(st.ModTime())
	info.LockAge = info.LockAgeDur.Truncate(time.Second).String()

	raw, err := os.ReadFile(lockPath)
	if err != nil {
		info.LockError = err.Error()
		return info, nil
	}
	for _, line := range strings.Split(string(raw), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k = strings.TrimSpace(k)
		v = strings.TrimSpace(v)
		switch k {
		case "pid":
			info.LockPID = v
		case "created_at":
			info.LockCreated = v
			if ts, err := time.Parse(time.RFC3339Nano, v); err == nil {
				info.LockAgeDur = time.Since(ts)
				info.LockAge = info.LockAgeDur.Truncate(time.Second).String()
			}
		case "host":
			info.LockHost = v
		case "user":
			info.LockUser = v
		}
	}
	return info, nil
}
