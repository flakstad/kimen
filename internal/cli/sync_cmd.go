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

	"kimen/internal/bundle"
	"kimen/internal/exitcode"
)

type syncResult struct {
	OK                bool     `json:"ok"`
	Action            string   `json:"action"`
	Remote            string   `json:"remote"`
	RemoteType        string   `json:"remote_type,omitempty"`
	RemotePath        string   `json:"remote_path,omitempty"`
	BundlePath        string   `json:"bundle_path,omitempty"`
	VaultPath         string   `json:"vault_path,omitempty"`
	RemoteRev         string   `json:"remote_rev,omitempty"`
	LastSeenRev       string   `json:"last_seen_rev,omitempty"`
	BackupPath        string   `json:"backup_path,omitempty"`
	DryRun            bool     `json:"dry_run,omitempty"`
	WouldBackup       bool     `json:"would_backup,omitempty"`
	HasRemote         bool     `json:"has_remote"`
	HasLock           bool     `json:"has_lock"`
	HasLocal          bool     `json:"has_local"`
	InSync            bool     `json:"in_sync"`
	CanPush           bool     `json:"can_push"`
	NeedsPull         bool     `json:"needs_pull"`
	LockPath          string   `json:"lock_path,omitempty"`
	LockAge           string   `json:"lock_age,omitempty"`
	LockPID           string   `json:"lock_pid,omitempty"`
	LockHost          string   `json:"lock_host,omitempty"`
	LockUser          string   `json:"lock_user,omitempty"`
	LockCreated       string   `json:"lock_created,omitempty"`
	LockError         string   `json:"lock_error,omitempty"`
	StaleLockBroken   bool     `json:"stale_lock_broken,omitempty"`
	LockBlocksPush    bool     `json:"lock_blocks_push"`
	LikelyStale       bool     `json:"likely_stale"`
	LockAgeSeconds    int64    `json:"lock_age_seconds"`
	Blockers          []string `json:"blockers,omitempty"`
	RecommendedAction string   `json:"recommended_action,omitempty"`
}

type syncStatusResult struct {
	OK                bool     `json:"ok"`
	Action            string   `json:"action"`
	Remote            string   `json:"remote"`
	RemoteType        string   `json:"remote_type,omitempty"`
	RemotePath        string   `json:"remote_path,omitempty"`
	BundlePath        string   `json:"bundle_path,omitempty"`
	VaultPath         string   `json:"vault_path,omitempty"`
	RemoteRev         string   `json:"remote_rev,omitempty"`
	LastSeenRev       string   `json:"last_seen_rev,omitempty"`
	HasRemote         bool     `json:"has_remote"`
	HasLock           bool     `json:"has_lock"`
	HasLocal          bool     `json:"has_local"`
	InSync            bool     `json:"in_sync"`
	CanPush           bool     `json:"can_push"`
	NeedsPull         bool     `json:"needs_pull"`
	LockPath          string   `json:"lock_path,omitempty"`
	LockAge           string   `json:"lock_age,omitempty"`
	LockPID           string   `json:"lock_pid,omitempty"`
	LockHost          string   `json:"lock_host,omitempty"`
	LockUser          string   `json:"lock_user,omitempty"`
	LockCreated       string   `json:"lock_created,omitempty"`
	LockError         string   `json:"lock_error,omitempty"`
	LockBlocksPush    bool     `json:"lock_blocks_push"`
	LikelyStale       bool     `json:"likely_stale"`
	LockAgeSeconds    int64    `json:"lock_age_seconds"`
	Blockers          []string `json:"blockers,omitempty"`
	RecommendedAction string   `json:"recommended_action,omitempty"`
}

type syncErrorResult struct {
	OK                bool   `json:"ok"`
	Error             string `json:"error"`
	ExitCode          int    `json:"exit_code"`
	Reason            string `json:"reason,omitempty"`
	ExpectedRev       string `json:"expected_rev,omitempty"`
	ActualRev         string `json:"actual_rev,omitempty"`
	RecommendedAction string `json:"recommended_action,omitempty"`
}

type syncConflictResult struct {
	OK                bool     `json:"ok"`
	Action            string   `json:"action"`
	Remote            string   `json:"remote"`
	RemoteType        string   `json:"remote_type,omitempty"`
	RemotePath        string   `json:"remote_path,omitempty"`
	BundlePath        string   `json:"bundle_path,omitempty"`
	RemoteRev         string   `json:"remote_rev,omitempty"`
	LastSeenRev       string   `json:"last_seen_rev,omitempty"`
	HasRemote         bool     `json:"has_remote"`
	HasLock           bool     `json:"has_lock"`
	HasConflict       bool     `json:"has_conflict"`
	Reason            string   `json:"reason,omitempty"`
	ExpectedRev       string   `json:"expected_rev,omitempty"`
	ActualRev         string   `json:"actual_rev,omitempty"`
	Message           string   `json:"message,omitempty"`
	LockPath          string   `json:"lock_path,omitempty"`
	LockAge           string   `json:"lock_age,omitempty"`
	LockPID           string   `json:"lock_pid,omitempty"`
	LockHost          string   `json:"lock_host,omitempty"`
	LockUser          string   `json:"lock_user,omitempty"`
	LockCreated       string   `json:"lock_created,omitempty"`
	LockError         string   `json:"lock_error,omitempty"`
	LockBlocksPush    bool     `json:"lock_blocks_push"`
	LikelyStale       bool     `json:"likely_stale"`
	LockAgeSeconds    int64    `json:"lock_age_seconds"`
	Blockers          []string `json:"blockers,omitempty"`
	RecommendedAction string   `json:"recommended_action,omitempty"`
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

type syncPreflightCheckResult struct {
	Name              string         `json:"name"`
	Command           string         `json:"command"`
	OK                bool           `json:"ok"`
	ExitCode          int            `json:"exit_code"`
	Error             string         `json:"error,omitempty"`
	RecommendedAction string         `json:"recommended_action,omitempty"`
	Payload           map[string]any `json:"payload,omitempty"`
}

type syncPreflightResult struct {
	OK                bool                       `json:"ok"`
	Action            string                     `json:"action"`
	Remote            string                     `json:"remote,omitempty"`
	Strict            bool                       `json:"strict"`
	ExitCode          int                        `json:"exit_code"`
	CheckCount        int                        `json:"check_count"`
	FailedCount       int                        `json:"failed_count"`
	FailedChecks      []string                   `json:"failed_checks,omitempty"`
	FailedCheck       string                     `json:"failed_check,omitempty"`
	RecommendedAction string                     `json:"recommended_action,omitempty"`
	Checks            []syncPreflightCheckResult `json:"checks"`
}

type syncAutoResult struct {
	OK                   bool                       `json:"ok"`
	Action               string                     `json:"action"`
	Remote               string                     `json:"remote,omitempty"`
	Mode                 string                     `json:"mode"`
	Strict               bool                       `json:"strict"`
	DryRun               bool                       `json:"dry_run,omitempty"`
	Check                bool                       `json:"check,omitempty"`
	NoDoctor             bool                       `json:"no_doctor,omitempty"`
	Decision             string                     `json:"decision"`
	ExitCode             int                        `json:"exit_code"`
	LocalChanged         bool                       `json:"local_changed"`
	LocalChangeUncertain bool                       `json:"local_change_uncertain,omitempty"`
	Reason               string                     `json:"reason,omitempty"`
	RecommendedAction    string                     `json:"recommended_action,omitempty"`
	Status               *syncStatusResult          `json:"status,omitempty"`
	Steps                []syncPreflightCheckResult `json:"steps"`
}

const (
	syncPreflightCheckDoctor     = "doctor"
	syncPreflightCheckStatus     = "sync_status"
	syncPreflightCheckConflicts  = "sync_conflicts"
	syncPreflightCheckPullDryRun = "sync_pull_dry_run"
	syncPreflightCheckPushDryRun = "sync_push_dry_run"
	syncAutoCheckSyncPush        = "sync_push"
	syncAutoCheckSyncPull        = "sync_pull"
)

var syncPreflightCheckOrder = []string{
	syncPreflightCheckDoctor,
	syncPreflightCheckStatus,
	syncPreflightCheckConflicts,
	syncPreflightCheckPullDryRun,
	syncPreflightCheckPushDryRun,
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
	var remoteName string
	var profile string
	var bundleIn string
	var identity string
	var staleThreshold time.Duration
	var strict bool
	var dryRun bool
	var checkOnly bool
	var noDoctor bool
	var allowMissingVault bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "sync",
		Short: "Sync vault bundles with configured remotes",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, args []string) error {
			if staleThreshold < 0 {
				return syncCommandError(cmd, jsonOut, errors.New("--stale-threshold must be >= 0"))
			}
			if checkOnly && dryRun {
				return syncCommandError(cmd, jsonOut, errors.New("--check and --dry-run cannot be used together"))
			}
			result := syncAutoResult{
				OK:       true,
				Action:   "sync",
				Mode:     "apply",
				Strict:   strict,
				DryRun:   dryRun,
				Check:    checkOnly,
				NoDoctor: noDoctor,
				Decision: "noop",
				Steps:    make([]syncPreflightCheckResult, 0, 4),
			}
			if checkOnly {
				result.Mode = "check"
			}
			if dryRun {
				result.Mode = "dry_run"
			}

			appendStep := func(step syncPreflightCheckResult) {
				result.Steps = append(result.Steps, step)
			}
			fail := func(step syncPreflightCheckResult) error {
				appendStep(step)
				code := step.ExitCode
				if code == 0 {
					code = exitcode.CodeSyncFailed
				}
				result.OK = false
				result.Decision = "blocked"
				result.ExitCode = code
				result.Reason = syncReasonFromPayload(step.Payload)
				result.RecommendedAction = strings.TrimSpace(step.RecommendedAction)
				if result.RecommendedAction == "" {
					result.RecommendedAction = "manual_review"
				}
				return renderSyncAutoAndExit(cmd, result, jsonOut)
			}

			if !noDoctor {
				doctorArgs := buildSyncPreflightDoctorArgs(profile, bundleIn, identity, strict, allowMissingVault)
				doctorStep := runSyncPreflightCheck(syncPreflightCheckDoctor, doctorArgs)
				if !doctorStep.OK {
					return fail(doctorStep)
				}
				appendStep(doctorStep)
			}

			statusArgs := buildSyncPreflightStatusArgs(remoteName, staleThreshold, false)
			statusStep := runSyncPreflightCheck(syncPreflightCheckStatus, statusArgs)
			if !statusStep.OK {
				return fail(statusStep)
			}
			appendStep(statusStep)

			status, err := decodeSyncStatusPayload(statusStep.Payload)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			result.Status = &status
			result.Remote = status.Remote
			result.RecommendedAction = status.RecommendedAction

			localChanged, uncertain, err := detectLocalChangesSinceBaseline(status)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			result.LocalChanged = localChanged
			result.LocalChangeUncertain = uncertain

			decision, decisionErr := chooseSyncAutoDecision(status, localChanged, uncertain)
			result.Decision = decision
			if decisionErr != nil {
				result.OK = false
				result.Decision = "blocked"
				result.ExitCode = syncExitCode(decisionErr)
				result.RecommendedAction = status.RecommendedAction
				if details, ok := syncConflictDetailsFromError(decisionErr); ok {
					result.Reason = details.Reason
					result.RecommendedAction = recommendedActionForConflictReason(details.Reason)
				} else if details, ok := syncConditionDetailsFromError(decisionErr); ok {
					result.Reason = details.Reason
					if strings.TrimSpace(details.RecommendedAction) != "" {
						result.RecommendedAction = details.RecommendedAction
					}
				}
				return renderSyncAutoAndExit(cmd, result, jsonOut)
			}
			if result.Decision == "noop" || checkOnly {
				if checkOnly {
					switch result.Decision {
					case "push":
						result.Decision = "would_push"
					case "pull":
						result.Decision = "would_pull"
					}
				}
				return renderSyncAutoAndExit(cmd, result, jsonOut)
			}

			switch result.Decision {
			case "push":
				if dryRun {
					step := runSyncPreflightCheck(syncPreflightCheckPushDryRun, buildSyncPreflightPushArgs(remoteName))
					if !step.OK {
						return fail(step)
					}
					appendStep(step)
					result.Decision = "would_push"
					return renderSyncAutoAndExit(cmd, result, jsonOut)
				}
				step := runSyncPreflightCheck(syncAutoCheckSyncPush, buildSyncAutoPushArgs(remoteName))
				if !step.OK {
					return fail(step)
				}
				appendStep(step)
				return renderSyncAutoAndExit(cmd, result, jsonOut)
			case "pull":
				if dryRun {
					step := runSyncPreflightCheck(syncPreflightCheckPullDryRun, buildSyncPreflightPullArgs(remoteName))
					if !step.OK {
						return fail(step)
					}
					appendStep(step)
					result.Decision = "would_pull"
					return renderSyncAutoAndExit(cmd, result, jsonOut)
				}
				step := runSyncPreflightCheck(syncAutoCheckSyncPull, buildSyncAutoPullArgs(remoteName))
				if !step.OK {
					return fail(step)
				}
				appendStep(step)
				return renderSyncAutoAndExit(cmd, result, jsonOut)
			default:
				return renderSyncAutoAndExit(cmd, result, jsonOut)
			}
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().StringVar(&profile, "profile", "", "profile name passed to doctor")
	cmd.Flags().StringVar(&bundleIn, "bundle-in", "", "bundle file passed to doctor")
	cmd.Flags().StringVar(&identity, "identity", "", "identity file passed to doctor")
	cmd.Flags().DurationVar(&staleThreshold, "stale-threshold", 0, "stale lock threshold passed to sync status")
	cmd.Flags().BoolVar(&strict, "strict", false, "treat doctor warnings as failures")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "plan and validate the selected sync action without mutation")
	cmd.Flags().BoolVar(&checkOnly, "check", false, "run checks and action selection only (no push/pull)")
	cmd.Flags().BoolVar(&noDoctor, "no-doctor", false, "skip doctor check before action selection")
	cmd.Flags().BoolVar(&allowMissingVault, "allow-missing-vault", false, "pass --allow-missing-vault to doctor")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.AddCommand(newSyncPreflightCommand())
	cmd.AddCommand(newSyncStatusCommand())
	cmd.AddCommand(newSyncConflictsCommand())
	cmd.AddCommand(newSyncResetBaselineCommand())
	cmd.AddCommand(newSyncUnlockCommand())
	cmd.AddCommand(newSyncRestoreCommand())
	cmd.AddCommand(newSyncPushCommand())
	cmd.AddCommand(newSyncPullCommand())
	return cmd
}

func newSyncPreflightCommand() *cobra.Command {
	var remoteName string
	var profile string
	var bundleIn string
	var identity string
	var staleThreshold time.Duration
	var strict bool
	var jsonOut bool
	var allowMissingVault bool
	var onlyChecks []string
	var skipChecks []string

	cmd := &cobra.Command{
		Use:   "preflight",
		Short: "Run a full sync readiness gate (doctor + strict sync checks + dry-runs)",
		RunE: func(cmd *cobra.Command, args []string) error {
			if staleThreshold < 0 {
				return syncCommandError(cmd, jsonOut, errors.New("--stale-threshold must be >= 0"))
			}

			selectedChecks, err := resolveSyncPreflightChecks(onlyChecks, skipChecks)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			checks := make([]syncPreflightCheckResult, 0, len(selectedChecks))
			for _, checkName := range selectedChecks {
				checkArgs, err := buildSyncPreflightArgs(
					checkName,
					remoteName,
					profile,
					bundleIn,
					identity,
					staleThreshold,
					strict,
					allowMissingVault,
				)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				checks = append(checks, runSyncPreflightCheck(checkName, checkArgs))
			}

			result := buildSyncPreflightResult(checks, remoteName, strict)

			if jsonOut {
				if err := json.NewEncoder(cmd.OutOrStdout()).Encode(result); err != nil {
					return err
				}
			} else {
				renderSyncPreflightHuman(cmd, result)
			}

			if result.ExitCode != 0 {
				return exitcode.New(result.ExitCode, errors.New("sync preflight failed"))
			}
			return nil
		},
	}

	cmd.Flags().StringVar(&remoteName, "remote", "", "remote name (defaults to the only configured remote)")
	cmd.Flags().StringVar(&profile, "profile", "", "profile name passed to doctor")
	cmd.Flags().StringVar(&bundleIn, "bundle-in", "", "bundle file passed to doctor")
	cmd.Flags().StringVar(&identity, "identity", "", "identity file passed to doctor")
	cmd.Flags().DurationVar(&staleThreshold, "stale-threshold", 0, "stale lock threshold passed to sync status/conflicts")
	cmd.Flags().BoolVar(&strict, "strict", false, "use strict doctor/status/conflicts checks")
	cmd.Flags().BoolVar(&allowMissingVault, "allow-missing-vault", false, "pass --allow-missing-vault to doctor")
	cmd.Flags().StringSliceVar(&onlyChecks, "only", nil, "run only selected checks (doctor|status|conflicts|pull|push)")
	cmd.Flags().StringSliceVar(&skipChecks, "skip", nil, "skip selected checks (doctor|status|conflicts|pull|push)")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSyncStatusCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var staleThreshold time.Duration
	var strict bool
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
			remoteRev, hasRemote, bundleRef, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lockInfo := pushLockInfo{}
			if remoteSupportsPushLock(remote) {
				lockInfo, err = readRemotePushLockInfo(bundleRef)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
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
			missingRecipient := strings.TrimSpace(remote.Recipient) == ""
			missingIdentity := strings.TrimSpace(remote.Identity) == ""
			if lockBlocksPush {
				canPush = false
			}
			if !hasLocal {
				canPush = false
			}
			if missingRecipient {
				canPush = false
			}
			likelyStale := false
			if lockInfo.HasLock && staleThreshold > 0 && lockInfo.LockAgeDur >= staleThreshold {
				likelyStale = true
			}
			conflictDetails := detectSyncConflict(strings.TrimSpace(lastSeen), remoteRev, hasRemote)
			blockers := make([]string, 0, 5)
			if !hasLocal {
				blockers = append(blockers, "local_vault_missing")
			}
			if lockBlocksPush {
				blockers = append(blockers, "remote_lock_present")
			}
			if missingRecipient {
				blockers = append(blockers, "remote_recipient_missing")
			}
			if needsPull && missingIdentity {
				blockers = append(blockers, "remote_identity_missing")
			}
			if conflictDetails.HasConflict {
				blockers = append(blockers, conflictDetails.Reason)
			}
			recommended := "none"
			switch {
			case needsPull && missingIdentity:
				recommended = "configure_remote_identity"
			case !hasLocal && !hasRemote:
				recommended = "vault_init"
			case !hasLocal && hasRemote:
				recommended = "sync_pull"
			case missingRecipient && !needsPull:
				recommended = "configure_remote_recipient"
			case conflictDetails.HasConflict && conflictDetails.Reason == "remote_disappeared":
				recommended = "sync_reset_baseline_or_remote_recreate"
			case needsPull || (conflictDetails.HasConflict && conflictDetails.Reason != "remote_disappeared"):
				recommended = "sync_pull"
			case lockBlocksPush:
				recommended = "wait_or_sync_unlock"
			case canPush:
				recommended = "sync_push"
			}

			res := syncStatusResult{
				OK:                true,
				Action:            "sync_status",
				Remote:            remote.Name,
				RemoteType:        remote.Type,
				RemotePath:        remote.Path,
				BundlePath:        bundleRef,
				VaultPath:         vaultPath,
				RemoteRev:         remoteRev,
				LastSeenRev:       lastSeen,
				HasRemote:         hasRemote,
				HasLock:           lockInfo.HasLock,
				HasLocal:          hasLocal,
				InSync:            inSync,
				CanPush:           canPush,
				NeedsPull:         needsPull,
				LockPath:          lockInfo.LockPath,
				LockAge:           lockInfo.LockAge,
				LockPID:           lockInfo.LockPID,
				LockHost:          lockInfo.LockHost,
				LockUser:          lockInfo.LockUser,
				LockCreated:       lockInfo.LockCreated,
				LockError:         lockInfo.LockError,
				LockBlocksPush:    lockBlocksPush,
				LikelyStale:       likelyStale,
				LockAgeSeconds:    int64(lockInfo.LockAgeDur.Seconds()),
				Blockers:          blockers,
				RecommendedAction: recommended,
			}
			if strict {
				switch {
				case conflictDetails.HasConflict:
					return syncCommandError(cmd, jsonOut, &syncConflictError{Details: conflictDetails})
				case lockBlocksPush:
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            "remote_lock_present",
						Message:           fmt.Sprintf("remote push lock exists: %s (another sync push may be in progress)", lockInfo.LockPath),
						RecommendedAction: "wait_or_sync_unlock",
					})
				case !hasLocal:
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            "local_vault_missing",
						Message:           "local vault file not found",
						RecommendedAction: recommended,
					})
				case missingRecipient:
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            "remote_recipient_missing",
						Message:           "remote recipient is not configured (set --recipient on `remote add`)",
						RecommendedAction: recommended,
					})
				case needsPull && missingIdentity:
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            "remote_identity_missing",
						Message:           "remote identity is not configured (set --identity on `remote add`)",
						RecommendedAction: recommended,
					})
				case !canPush:
					reason := "push_blocked"
					if len(blockers) > 0 {
						reason = blockers[0]
					}
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            reason,
						Message:           fmt.Sprintf("sync status indicates push is blocked (blockers=%s)", strings.Join(blockers, ",")),
						RecommendedAction: recommended,
					})
				}
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
			if len(blockers) == 0 {
				fmt.Fprintln(cmd.OutOrStdout(), "blockers: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "blockers: %s\n", strings.Join(blockers, ","))
			}
			fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", recommended)
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
	cmd.Flags().BoolVar(&strict, "strict", false, "exit non-zero when push is currently blocked")
	return cmd
}

func newSyncConflictsCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var staleThreshold time.Duration
	var strict bool
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
			remoteRev, hasRemote, bundleRef, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			lockInfo := pushLockInfo{}
			if remoteSupportsPushLock(remote) {
				lockInfo, err = readRemotePushLockInfo(bundleRef)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
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
			blockers := make([]string, 0, 2)
			if lockBlocksPush {
				blockers = append(blockers, "remote_lock_present")
			}
			if details.HasConflict {
				blockers = append(blockers, details.Reason)
			}
			recommended := "none"
			switch {
			case details.HasConflict && details.Reason != "remote_disappeared":
				recommended = "sync_pull"
			case details.HasConflict && details.Reason == "remote_disappeared":
				recommended = "sync_reset_baseline_or_remote_recreate"
			case lockBlocksPush:
				recommended = "wait_or_sync_unlock"
			}
			res := syncConflictResult{
				OK:                true,
				Action:            "sync_conflicts",
				Remote:            remote.Name,
				RemoteType:        remote.Type,
				RemotePath:        remote.Path,
				BundlePath:        bundleRef,
				RemoteRev:         remoteRev,
				LastSeenRev:       lastSeen,
				HasRemote:         hasRemote,
				HasLock:           lockInfo.HasLock,
				HasConflict:       details.HasConflict,
				Reason:            details.Reason,
				ExpectedRev:       details.ExpectedRev,
				ActualRev:         details.ActualRev,
				Message:           details.Message,
				LockPath:          lockInfo.LockPath,
				LockAge:           lockInfo.LockAge,
				LockPID:           lockInfo.LockPID,
				LockHost:          lockInfo.LockHost,
				LockUser:          lockInfo.LockUser,
				LockCreated:       lockInfo.LockCreated,
				LockError:         lockInfo.LockError,
				LockBlocksPush:    lockBlocksPush,
				LikelyStale:       likelyStale,
				LockAgeSeconds:    int64(lockInfo.LockAgeDur.Seconds()),
				Blockers:          blockers,
				RecommendedAction: recommended,
			}
			if strict {
				if details.HasConflict {
					return syncCommandError(cmd, jsonOut, &syncConflictError{Details: details})
				}
				if lockBlocksPush {
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            "remote_lock_present",
						Message:           fmt.Sprintf("remote push lock exists: %s (another sync push may be in progress)", lockInfo.LockPath),
						RecommendedAction: "wait_or_sync_unlock",
					})
				}
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
				if len(blockers) == 0 {
					fmt.Fprintln(cmd.OutOrStdout(), "blockers: (none)")
				} else {
					fmt.Fprintf(cmd.OutOrStdout(), "blockers: %s\n", strings.Join(blockers, ","))
				}
				fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", recommended)
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
			if len(blockers) == 0 {
				fmt.Fprintln(cmd.OutOrStdout(), "blockers: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "blockers: %s\n", strings.Join(blockers, ","))
			}
			fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", recommended)
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
	cmd.Flags().BoolVar(&strict, "strict", false, "exit non-zero when conflicts or lock blockers are present")
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
				c.Sync[remote.Name] = syncConfig{
					LastSeenRev:  newRev,
					LastLocalRev: "",
				}
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
			if !remoteSupportsPushLock(remote) {
				return syncCommandError(cmd, jsonOut, errors.New("sync unlock is only supported for fs remotes"))
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
	var dryRun bool
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
			if dryRun && (lockWait > 0 || breakStaleLockAfter > 0) {
				return syncCommandError(cmd, jsonOut, errors.New("--dry-run cannot be combined with --lock-wait/--break-stale-lock-after"))
			}

			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if strings.TrimSpace(remote.Recipient) == "" {
				return syncCommandError(cmd, jsonOut, errors.New("remote recipient is not configured (set --recipient on `remote add`)"))
			}
			if !remoteSupportsPushLock(remote) && (lockWait > 0 || breakStaleLockAfter > 0) {
				return syncCommandError(cmd, jsonOut, errors.New("--lock-wait/--break-stale-lock-after are only supported for fs remotes"))
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

			remoteRev, hasRemote, bundleRef, err := remoteRevision(remote)
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
			if err := validateSealVaultWithRecipient(vaultPath, remote.Recipient); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if dryRun {
				lockInfo := pushLockInfo{}
				if remoteSupportsPushLock(remote) {
					bundlePath, err := remoteBundlePath(remote)
					if err != nil {
						return syncCommandError(cmd, jsonOut, err)
					}
					lockInfo, err = readRemotePushLockInfo(bundlePath)
					if err != nil {
						return syncCommandError(cmd, jsonOut, err)
					}
					if lockInfo.HasLock {
						return syncCommandError(cmd, jsonOut, &syncConditionError{
							Reason:            "remote_lock_present",
							Message:           fmt.Sprintf("remote push lock exists: %s (another sync push may be in progress; dry-run cannot acquire locks)", lockInfo.LockPath),
							RecommendedAction: "wait_or_sync_unlock",
						})
					}
				}
				if jsonOut {
					return json.NewEncoder(cmd.OutOrStdout()).Encode(syncResult{
						OK:             true,
						Action:         "sync_push_dry_run",
						Remote:         remote.Name,
						RemoteType:     remote.Type,
						RemotePath:     remote.Path,
						BundlePath:     bundleRef,
						RemoteRev:      remoteRev,
						LastSeenRev:    lastSeen,
						DryRun:         true,
						HasRemote:      hasRemote,
						HasLock:        lockInfo.HasLock,
						HasLocal:       true,
						CanPush:        true,
						LockPath:       lockInfo.LockPath,
						LockAge:        lockInfo.LockAge,
						LockPID:        lockInfo.LockPID,
						LockHost:       lockInfo.LockHost,
						LockUser:       lockInfo.LockUser,
						LockCreated:    lockInfo.LockCreated,
						LockError:      lockInfo.LockError,
						LockBlocksPush: lockInfo.HasLock,
						LikelyStale:    false,
						LockAgeSeconds: int64(lockInfo.LockAgeDur.Seconds()),
					})
				}
				fmt.Fprintf(cmd.OutOrStdout(), "dry-run: push %s\n", remote.Name)
				if strings.TrimSpace(remoteRev) == "" {
					fmt.Fprintln(cmd.OutOrStdout(), "remote-rev: (missing)")
				} else {
					fmt.Fprintf(cmd.OutOrStdout(), "remote-rev: %s\n", remoteRev)
				}
				if strings.TrimSpace(lastSeen) == "" {
					fmt.Fprintln(cmd.OutOrStdout(), "last-seen-rev: (none)")
				} else {
					fmt.Fprintf(cmd.OutOrStdout(), "last-seen-rev: %s\n", lastSeen)
				}
				fmt.Fprintln(cmd.OutOrStdout(), "can-push: true")
				return nil
			}

			releaseLock := func() {}
			staleLockBroken := false
			if remoteSupportsPushLock(remote) {
				bundlePath, err := remoteBundlePath(remote)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				releaseLock, staleLockBroken, err = acquireRemotePushLock(bundlePath, lockWait, breakStaleLockAfter)
				if err != nil {
					if errors.Is(err, errRemotePushLockExists) {
						return syncCommandError(cmd, jsonOut, &syncConditionError{
							Reason:            "remote_lock_present",
							Message:           err.Error(),
							RecommendedAction: "wait_or_sync_unlock",
						})
					}
					return syncCommandError(cmd, jsonOut, err)
				}
			}
			defer releaseLock()

			newRev := ""
			switch remoteType(remote) {
			case "fs":
				bundlePath, err := remoteBundlePath(remote)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				if err := sealVaultToRemoteAtomic(vaultPath, bundlePath, remote.Recipient); err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				newRev, _, err = fileRevision(bundlePath)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				bundleRef = bundlePath
			case "git":
				newRev, bundleRef, err = sealVaultToGitRemote(vaultPath, remote.Recipient, remote)
				if err != nil {
					if errors.Is(err, errGitPushRejected) {
						currentRev, currentHasRemote, _, revErr := remoteRevision(remote)
						if revErr == nil {
							details := detectSyncConflict(strings.TrimSpace(remoteRev), currentRev, currentHasRemote)
							if details.HasConflict {
								return syncCommandError(cmd, jsonOut, &syncConflictError{Details: details})
							}
						}
					}
					return syncCommandError(cmd, jsonOut, err)
				}
			default:
				return syncCommandError(cmd, jsonOut, fmt.Errorf("unsupported remote type %q", remote.Type))
			}

			localRev, hasLocal, err := localVaultRevision(vaultPath)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if !hasLocal {
				return syncCommandError(cmd, jsonOut, errors.New("local vault disappeared before baseline update"))
			}

			if c.Sync == nil {
				c.Sync = make(map[string]syncConfig)
			}
			c.Sync[remote.Name] = syncConfig{
				LastSeenRev:  newRev,
				LastLocalRev: localRev,
			}
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
					BundlePath:      bundleRef,
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
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "validate push preconditions without modifying remote/baseline")
	return cmd
}

func newSyncPullCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var noBackup bool
	var dryRun bool
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
			if strings.TrimSpace(remote.Identity) == "" {
				return syncCommandError(cmd, jsonOut, errors.New("remote identity is not configured (set --identity on `remote add`)"))
			}

			remoteRev, hasRemote, bundleRef, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if !hasRemote {
				return syncCommandError(cmd, jsonOut, errors.New("remote bundle is missing"))
			}
			bundlePath, cleanupBundle, err := materializeRemoteBundleForRead(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			defer cleanupBundle()

			vaultPath, err := defaultVaultPath()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if err := os.MkdirAll(filepath.Dir(vaultPath), 0o700); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			hasLocal := false
			if _, err := os.Stat(vaultPath); err == nil {
				hasLocal = true
			} else if !errors.Is(err, os.ErrNotExist) {
				return syncCommandError(cmd, jsonOut, err)
			}

			id, err := bundle.LoadIdentity(remote.Identity, false, nil)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if dryRun {
				if err := bundle.ValidateBundleWithIdentity(bundlePath, id); err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				lastSeen := ""
				if c.Sync != nil {
					lastSeen = strings.TrimSpace(c.Sync[remote.Name].LastSeenRev)
				}
				inSync := lastSeen != "" && lastSeen == remoteRev
				wouldBackup := hasLocal && !noBackup
				if jsonOut {
					return json.NewEncoder(cmd.OutOrStdout()).Encode(syncResult{
						OK:          true,
						Action:      "sync_pull_dry_run",
						Remote:      remote.Name,
						RemoteType:  remote.Type,
						RemotePath:  remote.Path,
						BundlePath:  bundleRef,
						VaultPath:   vaultPath,
						RemoteRev:   remoteRev,
						LastSeenRev: lastSeen,
						DryRun:      true,
						WouldBackup: wouldBackup,
						HasRemote:   true,
						HasLocal:    hasLocal,
						InSync:      inSync,
					})
				}
				fmt.Fprintf(cmd.OutOrStdout(), "dry-run: pull %s (rev=%s)\n", remote.Name, remoteRev)
				fmt.Fprintf(cmd.OutOrStdout(), "would-backup: %t\n", wouldBackup)
				fmt.Fprintf(cmd.OutOrStdout(), "in-sync: %t\n", inSync)
				return nil
			}

			backupPath := ""
			if !noBackup && hasLocal {
				backupPath, err = backupExistingVault(vaultPath)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
			}

			if err := bundle.OpenToVaultFile(bundlePath, vaultPath, id, true); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			localRev, hasLocal, err := localVaultRevision(vaultPath)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if !hasLocal {
				return syncCommandError(cmd, jsonOut, errors.New("local vault missing after pull"))
			}

			if c.Sync == nil {
				c.Sync = make(map[string]syncConfig)
			}
			c.Sync[remote.Name] = syncConfig{
				LastSeenRev:  remoteRev,
				LastLocalRev: localRev,
			}
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
					BundlePath:  bundleRef,
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
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "validate pull preconditions without modifying local vault/baseline")
	return cmd
}

func validatePushBaseline(lastSeen, remoteRev string, hasRemote bool) error {
	details := detectSyncConflict(lastSeen, remoteRev, hasRemote)
	if !details.HasConflict {
		return nil
	}
	return &syncConflictError{Details: details}
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

func validateSealVaultWithRecipient(vaultPath, recipient string) error {
	tmpDir, err := os.MkdirTemp("", "kimen-sync-push-dry-run-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tmpDir)
	tmpPath := filepath.Join(tmpDir, "vault.age")
	return bundle.SealVaultFile(vaultPath, tmpPath, []string{recipient})
}

func buildSyncPreflightDoctorArgs(profile, bundleIn, identity string, strict, allowMissingVault bool) []string {
	args := []string{"doctor", "--json"}
	if strict {
		args = append(args, "--strict")
	}
	if allowMissingVault {
		args = append(args, "--allow-missing-vault")
	}
	if strings.TrimSpace(profile) != "" {
		args = append(args, "--profile", strings.TrimSpace(profile))
	}
	if strings.TrimSpace(bundleIn) != "" {
		args = append(args, "--bundle-in", strings.TrimSpace(bundleIn))
	}
	if strings.TrimSpace(identity) != "" {
		args = append(args, "--identity", strings.TrimSpace(identity))
	}
	return args
}

func buildSyncPreflightStatusArgs(remoteName string, staleThreshold time.Duration, strict bool) []string {
	args := []string{"sync", "status", "--json"}
	if strict {
		args = append(args, "--strict")
	}
	if strings.TrimSpace(remoteName) != "" {
		args = append(args, "--remote", strings.TrimSpace(remoteName))
	}
	if staleThreshold > 0 {
		args = append(args, "--stale-threshold", staleThreshold.String())
	}
	return args
}

func buildSyncPreflightConflictsArgs(remoteName string, staleThreshold time.Duration, strict bool) []string {
	args := []string{"sync", "conflicts", "--json"}
	if strict {
		args = append(args, "--strict")
	}
	if strings.TrimSpace(remoteName) != "" {
		args = append(args, "--remote", strings.TrimSpace(remoteName))
	}
	if staleThreshold > 0 {
		args = append(args, "--stale-threshold", staleThreshold.String())
	}
	return args
}

func buildSyncPreflightPullArgs(remoteName string) []string {
	args := []string{"sync", "pull", "--dry-run", "--json"}
	if strings.TrimSpace(remoteName) != "" {
		args = append(args, "--remote", strings.TrimSpace(remoteName))
	}
	return args
}

func buildSyncPreflightPushArgs(remoteName string) []string {
	args := []string{"sync", "push", "--dry-run", "--json"}
	if strings.TrimSpace(remoteName) != "" {
		args = append(args, "--remote", strings.TrimSpace(remoteName))
	}
	return args
}

func buildSyncAutoPushArgs(remoteName string) []string {
	args := []string{"sync", "push", "--json"}
	if strings.TrimSpace(remoteName) != "" {
		args = append(args, "--remote", strings.TrimSpace(remoteName))
	}
	return args
}

func buildSyncAutoPullArgs(remoteName string) []string {
	args := []string{"sync", "pull", "--json"}
	if strings.TrimSpace(remoteName) != "" {
		args = append(args, "--remote", strings.TrimSpace(remoteName))
	}
	return args
}

func decodeSyncStatusPayload(payload map[string]any) (syncStatusResult, error) {
	if len(payload) == 0 {
		return syncStatusResult{}, errors.New("sync status returned empty payload")
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return syncStatusResult{}, err
	}
	var status syncStatusResult
	if err := json.Unmarshal(raw, &status); err != nil {
		return syncStatusResult{}, fmt.Errorf("decode sync status payload: %w", err)
	}
	if strings.TrimSpace(status.Action) == "" {
		return syncStatusResult{}, errors.New("sync status payload missing action")
	}
	return status, nil
}

func detectLocalChangesSinceBaseline(status syncStatusResult) (changed bool, uncertain bool, err error) {
	if !status.HasLocal {
		return false, false, nil
	}
	remoteName := strings.TrimSpace(status.Remote)
	if remoteName == "" {
		return true, true, nil
	}
	c, _, err := loadConfig()
	if err != nil {
		return false, false, err
	}
	syncState, ok := c.Sync[remoteName]
	if !ok {
		return true, true, nil
	}
	lastLocal := strings.TrimSpace(syncState.LastLocalRev)
	if lastLocal == "" {
		return true, true, nil
	}
	localRev, hasLocal, err := localVaultRevision(status.VaultPath)
	if err != nil {
		return false, false, err
	}
	if !hasLocal {
		return false, false, nil
	}
	return strings.TrimSpace(localRev) != lastLocal, false, nil
}

func chooseSyncAutoDecision(status syncStatusResult, localChanged, localChangeUncertain bool) (string, error) {
	if status.NeedsPull {
		conflict := detectSyncConflict(strings.TrimSpace(status.LastSeenRev), strings.TrimSpace(status.RemoteRev), status.HasRemote)
		if !status.HasLocal {
			return "pull", nil
		}
		if localChanged || localChangeUncertain {
			if conflict.HasConflict {
				return "blocked", &syncConflictError{Details: conflict}
			}
			return "blocked", &syncConditionError{
				Reason:            "manual_pull_required",
				Message:           "remote pull required but local vault has unpushed changes; run `kimen sync pull` manually and re-apply local changes",
				RecommendedAction: "sync_pull",
			}
		}
		return "pull", nil
	}
	if status.CanPush {
		if localChanged {
			return "push", nil
		}
		return "noop", nil
	}
	if status.LockBlocksPush {
		return "blocked", &syncConditionError{
			Reason:            "remote_lock_present",
			Message:           "remote push lock exists; wait or remove lock with `kimen sync unlock`",
			RecommendedAction: "wait_or_sync_unlock",
		}
	}
	if len(status.Blockers) > 0 {
		return "blocked", &syncConditionError{
			Reason:            status.Blockers[0],
			Message:           fmt.Sprintf("sync is blocked (blockers=%s)", strings.Join(status.Blockers, ",")),
			RecommendedAction: status.RecommendedAction,
		}
	}
	return "noop", nil
}

func syncReasonFromPayload(payload map[string]any) string {
	if len(payload) == 0 {
		return ""
	}
	if reason, ok := payload["reason"].(string); ok {
		return strings.TrimSpace(reason)
	}
	return ""
}

func renderSyncAutoAndExit(cmd *cobra.Command, result syncAutoResult, jsonOut bool) error {
	if result.ExitCode == 0 && !result.OK {
		result.ExitCode = exitcode.CodeSyncFailed
	}
	if jsonOut {
		if err := json.NewEncoder(cmd.OutOrStdout()).Encode(result); err != nil {
			return err
		}
	} else {
		renderSyncAutoHuman(cmd, result)
	}
	if result.ExitCode != 0 {
		return exitcode.New(result.ExitCode, errors.New("sync failed"))
	}
	return nil
}

func renderSyncAutoHuman(cmd *cobra.Command, result syncAutoResult) {
	fmt.Fprintf(cmd.OutOrStdout(), "sync (mode=%s strict=%t)\n", result.Mode, result.Strict)
	if strings.TrimSpace(result.Remote) != "" {
		fmt.Fprintf(cmd.OutOrStdout(), "remote: %s\n", result.Remote)
	}
	fmt.Fprintf(cmd.OutOrStdout(), "decision: %s\n", result.Decision)
	fmt.Fprintf(cmd.OutOrStdout(), "local-changed: %t\n", result.LocalChanged)
	if result.LocalChangeUncertain {
		fmt.Fprintln(cmd.OutOrStdout(), "local-change-uncertain: true")
	}
	for _, step := range result.Steps {
		if step.OK {
			fmt.Fprintf(cmd.OutOrStdout(), "[ok] %s\n", step.Name)
		} else {
			fmt.Fprintf(cmd.OutOrStdout(), "[fail] %s (exit=%d)\n", step.Name, step.ExitCode)
			if strings.TrimSpace(step.Error) != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "  error: %s\n", step.Error)
			}
		}
	}
	if strings.TrimSpace(result.RecommendedAction) != "" {
		fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", result.RecommendedAction)
	}
	if result.OK {
		fmt.Fprintln(cmd.OutOrStdout(), "sync: ok")
		return
	}
	fmt.Fprintf(cmd.OutOrStdout(), "sync: failed (exit=%d)\n", result.ExitCode)
}

func buildSyncPreflightArgs(checkName, remoteName, profile, bundleIn, identity string, staleThreshold time.Duration, strict, allowMissingVault bool) ([]string, error) {
	switch checkName {
	case syncPreflightCheckDoctor:
		return buildSyncPreflightDoctorArgs(profile, bundleIn, identity, strict, allowMissingVault), nil
	case syncPreflightCheckStatus:
		return buildSyncPreflightStatusArgs(remoteName, staleThreshold, strict), nil
	case syncPreflightCheckConflicts:
		return buildSyncPreflightConflictsArgs(remoteName, staleThreshold, strict), nil
	case syncPreflightCheckPullDryRun:
		return buildSyncPreflightPullArgs(remoteName), nil
	case syncPreflightCheckPushDryRun:
		return buildSyncPreflightPushArgs(remoteName), nil
	default:
		return nil, fmt.Errorf("unsupported preflight check %q", checkName)
	}
}

func runSyncPreflightCheck(name string, args []string) syncPreflightCheckResult {
	if strings.TrimSpace(name) == "" {
		name = syncPreflightCheckName(args)
	}
	out, errOut, err := runRootSubcommand(args)
	code := 0
	if err != nil {
		var ec *exitcode.Error
		if errors.As(err, &ec) {
			code = ec.Code
		} else {
			code = 1
		}
	}

	res := syncPreflightCheckResult{
		Name:     name,
		Command:  "kimen " + strings.Join(args, " "),
		OK:       code == 0,
		ExitCode: code,
	}

	payloadRaw := strings.TrimSpace(out)
	if code != 0 && strings.TrimSpace(errOut) != "" {
		payloadRaw = strings.TrimSpace(errOut)
	}
	if payloadRaw == "" {
		payloadRaw = strings.TrimSpace(errOut)
	}
	if payloadRaw != "" {
		var payload map[string]any
		if json.Unmarshal([]byte(payloadRaw), &payload) == nil {
			res.Payload = payload
			if code != 0 {
				if msg, ok := payload["error"].(string); ok && strings.TrimSpace(msg) != "" {
					res.Error = msg
				}
				if rec, ok := payload["recommended_action"].(string); ok {
					res.RecommendedAction = strings.TrimSpace(rec)
				}
			}
		} else if code != 0 {
			res.Error = payloadRaw
		}
	}
	if code != 0 && res.Error == "" && err != nil {
		res.Error = err.Error()
	}
	return res
}

func resolveSyncPreflightChecks(onlyRaw, skipRaw []string) ([]string, error) {
	selected := make(map[string]bool, len(syncPreflightCheckOrder))
	if len(onlyRaw) == 0 {
		for _, checkName := range syncPreflightCheckOrder {
			selected[checkName] = true
		}
	} else {
		for _, raw := range onlyRaw {
			checkName, err := normalizeSyncPreflightCheckName(raw)
			if err != nil {
				return nil, err
			}
			selected[checkName] = true
		}
	}
	for _, raw := range skipRaw {
		checkName, err := normalizeSyncPreflightCheckName(raw)
		if err != nil {
			return nil, err
		}
		selected[checkName] = false
	}

	out := make([]string, 0, len(syncPreflightCheckOrder))
	for _, checkName := range syncPreflightCheckOrder {
		if selected[checkName] {
			out = append(out, checkName)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no preflight checks selected (available: %s)", strings.Join(syncPreflightCheckOrder, ", "))
	}
	return out, nil
}

func normalizeSyncPreflightCheckName(raw string) (string, error) {
	token := strings.TrimSpace(strings.ToLower(raw))
	switch token {
	case "doctor":
		return syncPreflightCheckDoctor, nil
	case "status", "sync_status", "sync-status":
		return syncPreflightCheckStatus, nil
	case "conflicts", "sync_conflicts", "sync-conflicts":
		return syncPreflightCheckConflicts, nil
	case "pull", "sync_pull_dry_run", "sync-pull-dry-run":
		return syncPreflightCheckPullDryRun, nil
	case "push", "sync_push_dry_run", "sync-push-dry-run":
		return syncPreflightCheckPushDryRun, nil
	default:
		return "", fmt.Errorf("unknown preflight check %q (available: doctor, status, conflicts, pull, push)", raw)
	}
}

func buildSyncPreflightResult(checks []syncPreflightCheckResult, remoteName string, strict bool) syncPreflightResult {
	failed := make([]string, 0, len(checks))
	exitCode := 0
	recommended := ""
	failedCheck := ""
	for _, check := range checks {
		if check.OK {
			continue
		}
		failed = append(failed, check.Name)
		if failedCheck == "" {
			failedCheck = check.Name
		}
		if recommended == "" && strings.TrimSpace(check.RecommendedAction) != "" {
			recommended = strings.TrimSpace(check.RecommendedAction)
		}
		switch check.ExitCode {
		case exitcode.CodeSyncConflict:
			exitCode = exitcode.CodeSyncConflict
		case 0:
			// no-op
		default:
			if exitCode == 0 {
				exitCode = exitcode.CodeSyncFailed
			}
		}
	}
	return syncPreflightResult{
		OK:                exitCode == 0,
		Action:            "sync_preflight",
		Remote:            strings.TrimSpace(remoteName),
		Strict:            strict,
		ExitCode:          exitCode,
		CheckCount:        len(checks),
		FailedCount:       len(failed),
		FailedChecks:      failed,
		FailedCheck:       failedCheck,
		RecommendedAction: recommended,
		Checks:            checks,
	}
}

func renderSyncPreflightHuman(cmd *cobra.Command, result syncPreflightResult) {
	fmt.Fprintf(cmd.OutOrStdout(), "sync preflight (strict=%t)\n", result.Strict)
	if strings.TrimSpace(result.Remote) != "" {
		fmt.Fprintf(cmd.OutOrStdout(), "remote: %s\n", result.Remote)
	}
	for _, check := range result.Checks {
		if check.OK {
			fmt.Fprintf(cmd.OutOrStdout(), "[ok] %s\n", check.Name)
		} else {
			fmt.Fprintf(cmd.OutOrStdout(), "[fail] %s (exit=%d)\n", check.Name, check.ExitCode)
			if strings.TrimSpace(check.Error) != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "  error: %s\n", check.Error)
			}
			if strings.TrimSpace(check.RecommendedAction) != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "  recommended-action: %s\n", check.RecommendedAction)
			}
		}
	}
	fmt.Fprintf(cmd.OutOrStdout(), "failed-checks: %d/%d\n", result.FailedCount, result.CheckCount)
	if strings.TrimSpace(result.RecommendedAction) != "" {
		fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", result.RecommendedAction)
	}
	if result.OK {
		fmt.Fprintln(cmd.OutOrStdout(), "preflight: ok")
		return
	}
	fmt.Fprintf(cmd.OutOrStdout(), "preflight: failed (exit=%d)\n", result.ExitCode)
}

func runRootSubcommand(args []string) (string, string, error) {
	root := NewRootCommand()
	var out bytes.Buffer
	var errBuf bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&errBuf)
	root.SetArgs(args)
	err := root.Execute()
	return out.String(), errBuf.String(), err
}

func syncPreflightCheckName(args []string) string {
	if len(args) == 0 {
		return "unknown"
	}
	if args[0] == "doctor" {
		return "doctor"
	}
	if len(args) >= 2 && args[0] == "sync" {
		switch args[1] {
		case "status":
			return "sync_status"
		case "conflicts":
			return "sync_conflicts"
		case "pull":
			return "sync_pull_dry_run"
		case "push":
			return "sync_push_dry_run"
		}
	}
	return strings.Join(args, "_")
}

func syncCommandError(cmd *cobra.Command, jsonOut bool, err error) error {
	if err == nil {
		return nil
	}
	code := syncExitCode(err)
	if jsonOut {
		resp := syncErrorResult{
			OK:       false,
			Error:    err.Error(),
			ExitCode: code,
		}
		if details, ok := syncConflictDetailsFromError(err); ok {
			resp.Reason = details.Reason
			resp.ExpectedRev = details.ExpectedRev
			resp.ActualRev = details.ActualRev
			resp.RecommendedAction = recommendedActionForConflictReason(details.Reason)
		} else if details, ok := syncConditionDetailsFromError(err); ok {
			resp.Reason = details.Reason
			resp.RecommendedAction = details.RecommendedAction
		}
		_ = json.NewEncoder(cmd.ErrOrStderr()).Encode(resp)
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

func recommendedActionForConflictReason(reason string) string {
	switch strings.TrimSpace(reason) {
	case "remote_changed", "no_local_baseline":
		return "sync_pull"
	case "remote_disappeared":
		return "sync_reset_baseline_or_remote_recreate"
	default:
		return ""
	}
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
			return nil, false, fmt.Errorf("%w: %s (another sync push may be in progress; use --lock-wait to wait)", errRemotePushLockExists, lockPath)
		}
		sleep := 250 * time.Millisecond
		remaining := time.Until(deadline)
		if remaining < sleep {
			sleep = remaining
		}
		if sleep <= 0 {
			return nil, false, fmt.Errorf("%w: %s (another sync push may be in progress)", errRemotePushLockExists, lockPath)
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
