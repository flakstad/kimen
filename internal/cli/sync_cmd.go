package cli

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/spf13/cobra"

	"kimen/internal/bundle"
	"kimen/internal/exitcode"
	"kimen/internal/vault"
)

type syncResult struct {
	OK                bool     `json:"ok"`
	Action            string   `json:"action"`
	ExitCode          int      `json:"exit_code"`
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
	Reconcile         bool     `json:"reconcile,omitempty"`
	CanReconcile      bool     `json:"can_reconcile,omitempty"`
	ConflictKeys      []string `json:"conflict_keys,omitempty"`
	MergedKeyCount    int      `json:"merged_key_count,omitempty"`
	LockBlocksPush    bool     `json:"lock_blocks_push"`
	LikelyStale       bool     `json:"likely_stale"`
	LockAgeSeconds    int64    `json:"lock_age_seconds"`
	Blockers          []string `json:"blockers,omitempty"`
	RecommendedAction string   `json:"recommended_action,omitempty"`
}

type syncStatusResult struct {
	OK                bool     `json:"ok"`
	Action            string   `json:"action"`
	ExitCode          int      `json:"exit_code"`
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
	ExitCode          int      `json:"exit_code"`
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
	ExitCode    int    `json:"exit_code"`
	Remote      string `json:"remote"`
	Mode        string `json:"mode"`
	PreviousRev string `json:"previous_rev,omitempty"`
	NewRev      string `json:"new_rev,omitempty"`
}

type syncRestoreResult struct {
	OK                bool   `json:"ok"`
	Action            string `json:"action"`
	ExitCode          int    `json:"exit_code"`
	VaultPath         string `json:"vault_path"`
	SourceBackupPath  string `json:"source_backup_path"`
	CurrentBackupPath string `json:"current_backup_path,omitempty"`
}

type syncUnlockResult struct {
	OK        bool   `json:"ok"`
	Action    string `json:"action"`
	ExitCode  int    `json:"exit_code"`
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

type syncChangesResult struct {
	OK                    bool     `json:"ok"`
	Action                string   `json:"action"`
	ExitCode              int      `json:"exit_code"`
	Remote                string   `json:"remote,omitempty"`
	HasBaseline           bool     `json:"has_baseline"`
	BaselineRev           string   `json:"baseline_rev,omitempty"`
	RemoteRev             string   `json:"remote_rev,omitempty"`
	HasRemote             bool     `json:"has_remote"`
	HasLocal              bool     `json:"has_local"`
	LocalChangedKeys      []string `json:"local_changed_keys,omitempty"`
	RemoteChangedKeys     []string `json:"remote_changed_keys,omitempty"`
	OverlappingKeys       []string `json:"overlapping_keys,omitempty"`
	ConflictKeys          []string `json:"conflict_keys,omitempty"`
	LocalOnlyChangedKeys  []string `json:"local_only_changed_keys,omitempty"`
	RemoteOnlyChangedKeys []string `json:"remote_only_changed_keys,omitempty"`
	CurrentOnlyLocalKeys  []string `json:"current_only_local_keys,omitempty"`
	CurrentOnlyRemoteKeys []string `json:"current_only_remote_keys,omitempty"`
	CurrentDifferentKeys  []string `json:"current_different_keys,omitempty"`
	CanReconcile          bool     `json:"can_reconcile"`
	RecommendedAction     string   `json:"recommended_action,omitempty"`
}

type syncResolveResult struct {
	OK                     bool     `json:"ok"`
	Action                 string   `json:"action"`
	ExitCode               int      `json:"exit_code"`
	Remote                 string   `json:"remote"`
	RemoteRev              string   `json:"remote_rev,omitempty"`
	LastSeenRev            string   `json:"last_seen_rev,omitempty"`
	Take                   string   `json:"take"`
	Keys                   []string `json:"keys"`
	ResolvedCount          int      `json:"resolved_count"`
	RemainingConflictKeys  []string `json:"remaining_conflict_keys,omitempty"`
	RemainingConflictCount int      `json:"remaining_conflict_count"`
	RecommendedAction      string   `json:"recommended_action,omitempty"`
}

type syncInitResult struct {
	OK                bool              `json:"ok"`
	Action            string            `json:"action"`
	ExitCode          int               `json:"exit_code"`
	Remote            string            `json:"remote"`
	Created           bool              `json:"created,omitempty"`
	Updated           bool              `json:"updated,omitempty"`
	DerivedRecipient  bool              `json:"derived_recipient,omitempty"`
	BaselineReset     bool              `json:"baseline_reset,omitempty"`
	CheckSkipped      bool              `json:"check_skipped,omitempty"`
	CheckOK           bool              `json:"check_ok,omitempty"`
	CheckError        string            `json:"check_error,omitempty"`
	RecommendedAction string            `json:"recommended_action,omitempty"`
	NextCommand       string            `json:"next_command,omitempty"`
	RemoteConfig      *remoteConfig     `json:"remote_config,omitempty"`
	Status            *syncStatusResult `json:"status,omitempty"`
}

type syncVaultSnapshot struct {
	Hashes  map[string]string
	Secrets map[string]vault.Secret
}

type syncChangeOp string

const (
	syncChangeUnchanged syncChangeOp = "unchanged"
	syncChangeAdded     syncChangeOp = "added"
	syncChangeRemoved   syncChangeOp = "removed"
	syncChangeModified  syncChangeOp = "modified"
)

type syncChangeAnalysis struct {
	HasBaseline           bool
	LocalOps              map[string]syncChangeOp
	RemoteOps             map[string]syncChangeOp
	LocalChangedKeys      []string
	RemoteChangedKeys     []string
	OverlappingKeys       []string
	ConflictKeys          []string
	LocalOnlyChangedKeys  []string
	RemoteOnlyChangedKeys []string
}

const (
	syncPreflightCheckDoctor     = "doctor"
	syncPreflightCheckStatus     = "sync_status"
	syncPreflightCheckConflicts  = "sync_conflicts"
	syncPreflightCheckPullDryRun = "sync_pull_dry_run"
	syncPreflightCheckPushDryRun = "sync_push_dry_run"
	syncAutoCheckSyncPush        = "sync_push"
	syncAutoCheckSyncPull        = "sync_pull"
	syncAutoCheckSyncReconcile   = "sync_pull_reconcile"
)

const syncRemoteFlagHelp = "remote name (selection: --remote, KIMEN_REMOTE, unique sync-state remote, origin, or only configured remote)"

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
	var terse bool
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
				return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
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
				return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
			}
			if result.Decision == "noop" || checkOnly {
				if checkOnly {
					switch result.Decision {
					case "push":
						result.Decision = "would_push"
					case "pull":
						result.Decision = "would_pull"
					case "pull_reconcile":
						result.Decision = "would_pull_reconcile"
					}
				}
				return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
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
					return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
				}
				step := runSyncPreflightCheck(syncAutoCheckSyncPush, buildSyncAutoPushArgs(remoteName))
				if !step.OK {
					return fail(step)
				}
				appendStep(step)
				return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
			case "pull":
				if dryRun {
					step := runSyncPreflightCheck(syncPreflightCheckPullDryRun, buildSyncPreflightPullArgs(remoteName))
					if !step.OK {
						return fail(step)
					}
					appendStep(step)
					result.Decision = "would_pull"
					return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
				}
				step := runSyncPreflightCheck(syncAutoCheckSyncPull, buildSyncAutoPullArgs(remoteName))
				if !step.OK {
					return fail(step)
				}
				appendStep(step)
				return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
			case "pull_reconcile":
				if dryRun {
					step := runSyncPreflightCheck(syncPreflightCheckPullDryRun, buildSyncPreflightPullReconcileArgs(remoteName))
					if !step.OK {
						return fail(step)
					}
					appendStep(step)
					result.Decision = "would_pull_reconcile"
					return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
				}
				step := runSyncPreflightCheck(syncAutoCheckSyncReconcile, buildSyncAutoPullReconcileArgs(remoteName))
				if !step.OK {
					return fail(step)
				}
				appendStep(step)
				return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
			default:
				return renderSyncAutoAndExit(cmd, result, jsonOut, terse)
			}
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
	cmd.Flags().StringVar(&profile, "profile", "", "profile name passed to doctor")
	cmd.Flags().StringVar(&bundleIn, "bundle-in", "", "bundle file passed to doctor")
	cmd.Flags().StringVar(&identity, "identity", "", "identity file passed to doctor")
	cmd.Flags().DurationVar(&staleThreshold, "stale-threshold", 0, "stale lock threshold passed to sync status")
	cmd.Flags().BoolVar(&strict, "strict", false, "treat doctor warnings as failures")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "plan and validate the selected sync action without mutation")
	cmd.Flags().BoolVar(&checkOnly, "check", false, "run checks and action selection only (no push/pull)")
	cmd.Flags().BoolVar(&noDoctor, "no-doctor", false, "skip doctor check before action selection")
	cmd.Flags().BoolVar(&allowMissingVault, "allow-missing-vault", false, "pass --allow-missing-vault to doctor")
	cmd.Flags().BoolVar(&terse, "terse", false, "human output: emit a single-line summary")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.AddCommand(newSyncPreflightCommand())
	cmd.AddCommand(newSyncChangesCommand())
	cmd.AddCommand(newSyncResolveCommand())
	cmd.AddCommand(newSyncStatusCommand())
	cmd.AddCommand(newSyncConflictsCommand())
	cmd.AddCommand(newSyncResetBaselineCommand())
	cmd.AddCommand(newSyncUnlockCommand())
	cmd.AddCommand(newSyncRestoreCommand())
	cmd.AddCommand(newSyncInitCommand())
	cmd.AddCommand(newSyncPushCommand())
	cmd.AddCommand(newSyncPullCommand())
	return cmd
}

func newSyncInitCommand() *cobra.Command {
	var remoteNameFlag string
	var remoteTypeFlag string
	var path string
	var recipient string
	var identity string
	var branch string
	var bundlePath string
	var update bool
	var noCheck bool
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "init [name]",
		Short: "Bootstrap a sync remote config and show the safest next command",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			remoteName := strings.TrimSpace(remoteNameFlag)
			if len(args) == 1 {
				argName := strings.TrimSpace(args[0])
				if argName == "" {
					return syncCommandError(cmd, jsonOut, errors.New("empty remote name"))
				}
				if remoteName != "" && remoteName != argName {
					return syncCommandError(cmd, jsonOut, errors.New("remote name mismatch between arg and --remote"))
				}
				remoteName = argName
			}
			if remoteName == "" {
				remoteName = "origin"
			}
			if !remoteNameRE.MatchString(remoteName) {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("invalid remote name %q", remoteName))
			}

			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			idx := findRemoteIndex(c.Remotes, remoteName)
			exists := idx >= 0
			if exists && !update {
				return syncCommandError(cmd, jsonOut, fmt.Errorf("remote %q already exists (use --update)", remoteName))
			}

			var r remoteConfig
			if exists {
				r = c.Remotes[idx]
			}
			r.Name = remoteName
			if cmd.Flags().Changed("type") {
				r.Type = normalizeRemoteType(remoteTypeFlag)
			} else if strings.TrimSpace(r.Type) == "" {
				r.Type = "fs"
			}
			if cmd.Flags().Changed("path") {
				r.Path = strings.TrimSpace(path)
			}
			if !exists && strings.TrimSpace(r.Path) == "" {
				return syncCommandError(cmd, jsonOut, errors.New("--path is required"))
			}
			if cmd.Flags().Changed("recipient") {
				r.Recipient = strings.TrimSpace(recipient)
			}
			if cmd.Flags().Changed("identity") {
				r.Identity = strings.TrimSpace(identity)
			}
			if cmd.Flags().Changed("branch") {
				r.Branch = strings.TrimSpace(branch)
			}
			if cmd.Flags().Changed("bundle-path") {
				r.BundlePath = strings.TrimSpace(bundlePath)
			}
			if remoteType(r) != "git" && (cmd.Flags().Changed("branch") || cmd.Flags().Changed("bundle-path")) {
				return syncCommandError(cmd, jsonOut, errors.New("--branch/--bundle-path are only valid for --type git"))
			}

			derivedRecipient := false
			if !cmd.Flags().Changed("recipient") &&
				strings.TrimSpace(r.Recipient) == "" &&
				strings.TrimSpace(r.Identity) != "" {
				id, err := bundle.LoadIdentity(strings.TrimSpace(r.Identity), false, nil)
				if err != nil {
					return syncCommandError(cmd, jsonOut, fmt.Errorf("derive recipient from identity: %w", err))
				}
				derived, err := bundle.RecipientForIdentity(id)
				if err != nil {
					return syncCommandError(cmd, jsonOut, fmt.Errorf("derive recipient from identity: %w", err))
				}
				r.Recipient = strings.TrimSpace(derived)
				derivedRecipient = r.Recipient != ""
			}

			applyRemoteDefaults(&r)
			if err := validateRemoteConfig(r); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			baselineReset := false
			if exists && c.Sync != nil {
				prev := c.Remotes[idx]
				if syncRemoteEndpointSignature(prev) != syncRemoteEndpointSignature(r) {
					if _, ok := c.Sync[remoteName]; ok {
						delete(c.Sync, remoteName)
						baselineReset = true
					}
					if len(c.Sync) == 0 {
						c.Sync = nil
					}
				}
			}

			created := false
			updated := false
			if exists {
				c.Remotes[idx] = r
				updated = true
			} else {
				c.Remotes = append(c.Remotes, r)
				sort.Slice(c.Remotes, func(i, j int) bool { return c.Remotes[i].Name < c.Remotes[j].Name })
				created = true
			}

			if _, err := saveConfig(c); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			result := syncInitResult{
				OK:               true,
				Action:           "sync_init",
				Remote:           remoteName,
				Created:          created,
				Updated:          updated,
				DerivedRecipient: derivedRecipient,
				BaselineReset:    baselineReset,
				RemoteConfig:     &r,
			}

			if noCheck {
				result.CheckSkipped = true
			} else {
				statusStep := runSyncPreflightCheck(syncPreflightCheckStatus, buildSyncPreflightStatusArgs(remoteName, 0, false))
				result.CheckOK = statusStep.OK
				if statusStep.OK {
					status, err := decodeSyncStatusPayload(statusStep.Payload)
					if err != nil {
						result.CheckOK = false
						result.CheckError = err.Error()
					} else {
						result.Status = &status
						result.RecommendedAction = strings.TrimSpace(status.RecommendedAction)
						result.NextCommand = syncInitNextCommand(remoteName, result.RecommendedAction)
					}
				} else {
					result.CheckError = strings.TrimSpace(statusStep.Error)
					if result.CheckError == "" {
						result.CheckError = "sync status check failed"
					}
					result.RecommendedAction = strings.TrimSpace(statusStep.RecommendedAction)
					result.NextCommand = syncInitNextCommand(remoteName, result.RecommendedAction)
				}
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(result)
			}

			if result.Created {
				fmt.Fprintf(cmd.OutOrStdout(), "ok (sync remote %s created)\n", remoteName)
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "ok (sync remote %s updated)\n", remoteName)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "type: %s\n", r.Type)
			fmt.Fprintf(cmd.OutOrStdout(), "path: %s\n", r.Path)
			if remoteType(r) == "git" {
				fmt.Fprintf(cmd.OutOrStdout(), "branch: %s\n", r.Branch)
				fmt.Fprintf(cmd.OutOrStdout(), "bundle-path: %s\n", r.BundlePath)
			}
			if strings.TrimSpace(r.Recipient) == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "recipient: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "recipient: %s\n", r.Recipient)
			}
			if strings.TrimSpace(r.Identity) == "" {
				fmt.Fprintln(cmd.OutOrStdout(), "identity: (none)")
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "identity: %s\n", r.Identity)
			}
			if result.DerivedRecipient {
				fmt.Fprintln(cmd.OutOrStdout(), "derived-recipient: true")
			}
			if result.BaselineReset {
				fmt.Fprintln(cmd.OutOrStdout(), "sync-baseline-reset: true")
			}
			if result.CheckSkipped {
				fmt.Fprintln(cmd.OutOrStdout(), "status-check: skipped (--no-check)")
				return nil
			}
			if result.CheckOK && result.Status != nil {
				fmt.Fprintf(cmd.OutOrStdout(), "status: ok (has_remote=%t has_local=%t needs_pull=%t can_push=%t)\n", result.Status.HasRemote, result.Status.HasLocal, result.Status.NeedsPull, result.Status.CanPush)
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "status: warning (%s)\n", result.CheckError)
			}
			if strings.TrimSpace(result.RecommendedAction) != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", result.RecommendedAction)
			}
			if strings.TrimSpace(result.NextCommand) != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "next: %s\n", result.NextCommand)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteNameFlag, "remote", "", "remote name (defaults to origin)")
	cmd.Flags().StringVar(&remoteTypeFlag, "type", "", "remote type (fs or git)")
	cmd.Flags().StringVar(&path, "path", "", "remote path (fs dir/.age path or git repo URL/path)")
	cmd.Flags().StringVar(&recipient, "recipient", "", "age recipient used for sync push")
	cmd.Flags().StringVar(&identity, "identity", "", "age identity file used for sync pull")
	cmd.Flags().StringVar(&branch, "branch", "", "git branch used for sync (default: main)")
	cmd.Flags().StringVar(&bundlePath, "bundle-path", "", "git-relative bundle path (default: vault.age)")
	cmd.Flags().BoolVar(&update, "update", false, "update existing remote instead of failing")
	cmd.Flags().BoolVar(&noCheck, "no-check", false, "skip post-init sync status check")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
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

	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
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

func newSyncChangesCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var terse bool

	cmd := &cobra.Command{
		Use:   "changes",
		Short: "Analyze key-level local/remote changes relative to sync baseline",
		RunE: func(cmd *cobra.Command, args []string) error {
			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remoteRev, hasRemote, _, err := remoteRevision(remote)
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
			pp, err := resolvePassphraseForVault(vaultPath, "", false)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(pp)

			localSnap := syncVaultSnapshot{Hashes: map[string]string{}}
			if hasLocal {
				localSnap, err = loadLocalVaultSnapshot(vaultPath, pp, false)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
			}
			remoteSnap := syncVaultSnapshot{Hashes: map[string]string{}}
			if hasRemote {
				remoteSnap, err = loadRemoteVaultSnapshot(remote, pp, false)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
			}

			syncState, hasSyncState := c.Sync[remote.Name]
			baseline := map[string]string(nil)
			if hasSyncState {
				baseline = syncState.BaselineSecretHashes
			}
			analysis := analyzeSyncChanges(baseline, localSnap.Hashes, remoteSnap.Hashes)
			onlyLocal, onlyRemote, different := diffSnapshotsCurrent(localSnap.Hashes, remoteSnap.Hashes)
			result := syncChangesResult{
				OK:                    true,
				Action:                "sync_changes",
				Remote:                remote.Name,
				HasBaseline:           analysis.HasBaseline,
				BaselineRev:           strings.TrimSpace(syncState.LastSeenRev),
				RemoteRev:             strings.TrimSpace(remoteRev),
				HasRemote:             hasRemote,
				HasLocal:              hasLocal,
				LocalChangedKeys:      analysis.LocalChangedKeys,
				RemoteChangedKeys:     analysis.RemoteChangedKeys,
				OverlappingKeys:       analysis.OverlappingKeys,
				ConflictKeys:          analysis.ConflictKeys,
				LocalOnlyChangedKeys:  analysis.LocalOnlyChangedKeys,
				RemoteOnlyChangedKeys: analysis.RemoteOnlyChangedKeys,
				CurrentOnlyLocalKeys:  onlyLocal,
				CurrentOnlyRemoteKeys: onlyRemote,
				CurrentDifferentKeys:  different,
				CanReconcile:          analysis.HasBaseline && len(analysis.ConflictKeys) == 0,
			}
			result.RecommendedAction = recommendedActionForSyncChangeAnalysis(analysis)

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(result)
			}
			if terse {
				fmt.Fprintf(
					cmd.OutOrStdout(),
					"remote=%s has_baseline=%t can_reconcile=%t local_changed=%d remote_changed=%d overlapping=%d conflicts=%d recommended_action=%s\n",
					result.Remote,
					result.HasBaseline,
					result.CanReconcile,
					len(result.LocalChangedKeys),
					len(result.RemoteChangedKeys),
					len(result.OverlappingKeys),
					len(result.ConflictKeys),
					result.RecommendedAction,
				)
				return nil
			}
			fmt.Fprintf(cmd.OutOrStdout(), "remote: %s\n", result.Remote)
			fmt.Fprintf(cmd.OutOrStdout(), "has-baseline: %t\n", result.HasBaseline)
			fmt.Fprintf(cmd.OutOrStdout(), "can-reconcile: %t\n", result.CanReconcile)
			fmt.Fprintf(cmd.OutOrStdout(), "local-changed-keys: %d\n", len(result.LocalChangedKeys))
			fmt.Fprintf(cmd.OutOrStdout(), "remote-changed-keys: %d\n", len(result.RemoteChangedKeys))
			fmt.Fprintf(cmd.OutOrStdout(), "overlapping-keys: %d\n", len(result.OverlappingKeys))
			fmt.Fprintf(cmd.OutOrStdout(), "conflict-keys: %d\n", len(result.ConflictKeys))
			if len(result.ConflictKeys) > 0 {
				fmt.Fprintf(cmd.OutOrStdout(), "conflict-list: %s\n", strings.Join(result.ConflictKeys, ","))
			}
			fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", result.RecommendedAction)
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&terse, "terse", false, "human output: emit a single-line summary")
	return cmd
}

func newSyncResolveCommand() *cobra.Command {
	var remoteName string
	var take string
	var keys []string
	var jsonOut bool

	cmd := &cobra.Command{
		Use:   "resolve",
		Short: "Resolve overlapping key conflicts by taking local or remote values",
		RunE: func(cmd *cobra.Command, args []string) error {
			take = strings.ToLower(strings.TrimSpace(take))
			if take != "local" && take != "remote" {
				return syncCommandError(cmd, jsonOut, errors.New("--take must be one of: local, remote"))
			}
			c, _, err := loadConfig()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remote, err := resolveRemote(c, remoteName)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remoteRev, hasRemote, _, err := remoteRevision(remote)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if !hasRemote {
				return syncCommandError(cmd, jsonOut, &syncConditionError{
					Reason:            reasonRemoteMissing,
					Message:           "remote bundle is missing",
					RecommendedAction: "sync_reset_baseline_or_remote_recreate",
				})
			}
			vaultPath, err := defaultVaultPath()
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if _, hasLocal, err := localVaultRevision(vaultPath); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			} else if !hasLocal {
				return syncCommandError(cmd, jsonOut, &syncConditionError{
					Reason:            reasonLocalVaultMissing,
					Message:           "local vault file not found",
					RecommendedAction: "sync_pull",
				})
			}

			pp, err := resolvePassphraseForVault(vaultPath, "", false)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			defer vault.Burn(pp)

			localSnap, err := loadLocalVaultSnapshot(vaultPath, pp, false)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			remoteSnap, err := loadRemoteVaultSnapshot(remote, pp, take == "remote")
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			syncState, hasSyncState := c.Sync[remote.Name]
			if !hasSyncState || syncState.BaselineSecretHashes == nil {
				return syncCommandError(cmd, jsonOut, &syncConditionError{
					Reason:            reasonReconcileBaselineMissing,
					Message:           "cannot resolve conflicts without baseline key hashes; run `kimen sync pull` first",
					RecommendedAction: "sync_pull",
				})
			}

			baseline := copyStringMap(syncState.BaselineSecretHashes)
			analysis := analyzeSyncChanges(baseline, localSnap.Hashes, remoteSnap.Hashes)
			if len(analysis.ConflictKeys) == 0 {
				return syncCommandError(cmd, jsonOut, &syncConditionError{
					Reason:            reasonNoOverlappingConflicts,
					Message:           "no overlapping key conflicts to resolve",
					RecommendedAction: "none",
				})
			}

			selectedKeys, err := resolveSyncResolveKeys(keys, analysis.ConflictKeys)
			if err != nil {
				return syncCommandError(cmd, jsonOut, &syncConditionError{
					Reason:            reasonResolveKeyNotConflict,
					Message:           err.Error(),
					RecommendedAction: "manual_reconcile",
				})
			}
			if len(selectedKeys) == 0 {
				selectedKeys = append([]string(nil), analysis.ConflictKeys...)
			}

			if take == "remote" {
				v, err := vault.Open(vaultPath, pp)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				for _, key := range selectedKeys {
					remoteSec, ok := remoteSnap.Secrets[key]
					if !ok {
						if err := v.DeleteSecret(context.Background(), key); err != nil && !errors.Is(err, vault.ErrSecretNotFound) {
							_ = v.Close()
							return syncCommandError(cmd, jsonOut, err)
						}
						continue
					}
					if err := v.PutSecret(context.Background(), vault.Secret{
						Name:  key,
						Type:  remoteSec.Type,
						Value: remoteSec.Value,
					}); err != nil {
						_ = v.Close()
						return syncCommandError(cmd, jsonOut, err)
					}
				}
				if err := v.Close(); err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
			}

			localResolved := copyStringMap(localSnap.Hashes)
			if localResolved == nil {
				localResolved = make(map[string]string)
			}
			baselineResolved := copyStringMap(syncState.BaselineSecretHashes)
			if baselineResolved == nil {
				baselineResolved = make(map[string]string)
			}
			for _, key := range selectedKeys {
				remoteHash, ok := remoteSnap.Hashes[key]
				if ok {
					baselineResolved[key] = remoteHash
					if take == "remote" {
						localResolved[key] = remoteHash
					}
					continue
				}
				delete(baselineResolved, key)
				if take == "remote" {
					delete(localResolved, key)
				}
			}
			if len(baselineResolved) == 0 {
				baselineResolved = nil
			}

			remaining := analyzeSyncChanges(baselineResolved, localResolved, remoteSnap.Hashes)
			syncState.BaselineSecretHashes = baselineResolved
			if len(remaining.RemoteChangedKeys) == 0 {
				syncState.LastSeenRev = strings.TrimSpace(remoteRev)
			}
			localRev, hasLocal, err := localVaultRevision(vaultPath)
			if err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}
			if !hasLocal {
				return syncCommandError(cmd, jsonOut, errors.New("local vault missing after sync resolve"))
			}
			syncState.LastLocalRev = localRev
			if c.Sync == nil {
				c.Sync = make(map[string]syncConfig)
			}
			c.Sync[remote.Name] = syncState
			if _, err := saveConfig(c); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			result := syncResolveResult{
				OK:                     true,
				Action:                 "sync_resolve",
				Remote:                 remote.Name,
				RemoteRev:              remoteRev,
				LastSeenRev:            syncState.LastSeenRev,
				Take:                   take,
				Keys:                   selectedKeys,
				ResolvedCount:          len(selectedKeys),
				RemainingConflictKeys:  remaining.ConflictKeys,
				RemainingConflictCount: len(remaining.ConflictKeys),
				RecommendedAction:      recommendedActionForSyncChangeAnalysis(remaining),
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(result)
			}
			fmt.Fprintf(cmd.OutOrStdout(), "remote: %s\n", result.Remote)
			fmt.Fprintf(cmd.OutOrStdout(), "take: %s\n", result.Take)
			fmt.Fprintf(cmd.OutOrStdout(), "resolved-keys: %d\n", result.ResolvedCount)
			fmt.Fprintf(cmd.OutOrStdout(), "remaining-conflicts: %d\n", result.RemainingConflictCount)
			if len(result.RemainingConflictKeys) > 0 {
				fmt.Fprintf(cmd.OutOrStdout(), "remaining-conflict-list: %s\n", strings.Join(result.RemainingConflictKeys, ","))
			}
			fmt.Fprintf(cmd.OutOrStdout(), "recommended-action: %s\n", result.RecommendedAction)
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
	cmd.Flags().StringVar(&take, "take", "", "resolution side for selected keys: local|remote")
	cmd.Flags().StringSliceVar(&keys, "key", nil, "conflict key(s) to resolve (repeatable or comma-separated); defaults to all conflict keys")
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	return cmd
}

func newSyncStatusCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var terse bool
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
				blockers = append(blockers, reasonLocalVaultMissing)
			}
			if lockBlocksPush {
				blockers = append(blockers, reasonRemoteLockPresent)
			}
			if missingRecipient {
				blockers = append(blockers, reasonRemoteRecipientMissing)
			}
			if needsPull && missingIdentity {
				blockers = append(blockers, reasonRemoteIdentityMissing)
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
			case conflictDetails.HasConflict && conflictDetails.Reason == reasonRemoteDisappeared:
				recommended = "sync_reset_baseline_or_remote_recreate"
			case needsPull || (conflictDetails.HasConflict && conflictDetails.Reason != reasonRemoteDisappeared):
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
						Reason:            reasonRemoteLockPresent,
						Message:           fmt.Sprintf("remote push lock exists: %s (another sync push may be in progress)", lockInfo.LockPath),
						RecommendedAction: "wait_or_sync_unlock",
					})
				case !hasLocal:
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            reasonLocalVaultMissing,
						Message:           "local vault file not found",
						RecommendedAction: recommended,
					})
				case missingRecipient:
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            reasonRemoteRecipientMissing,
						Message:           "remote recipient is not configured (set --recipient on `remote add`)",
						RecommendedAction: recommended,
					})
				case needsPull && missingIdentity:
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            reasonRemoteIdentityMissing,
						Message:           "remote identity is not configured (set --identity on `remote add`)",
						RecommendedAction: recommended,
					})
				case !canPush:
					reason := reasonPushBlocked
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
			if terse {
				blockers := "-"
				if len(res.Blockers) > 0 {
					blockers = strings.Join(res.Blockers, ",")
				}
				fmt.Fprintf(
					cmd.OutOrStdout(),
					"remote=%s in_sync=%t can_push=%t needs_pull=%t has_lock=%t blockers=%s recommended_action=%s\n",
					res.Remote, res.InSync, res.CanPush, res.NeedsPull, res.HasLock, blockers, res.RecommendedAction,
				)
				return nil
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
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&terse, "terse", false, "human output: emit a single-line summary")
	cmd.Flags().DurationVar(&staleThreshold, "stale-threshold", 0, "mark lock as likely stale when lock age is >= this duration")
	cmd.Flags().BoolVar(&strict, "strict", false, "exit non-zero when push is currently blocked")
	return cmd
}

func newSyncConflictsCommand() *cobra.Command {
	var remoteName string
	var jsonOut bool
	var terse bool
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
				blockers = append(blockers, reasonRemoteLockPresent)
			}
			if details.HasConflict {
				blockers = append(blockers, details.Reason)
			}
			recommended := "none"
			switch {
			case details.HasConflict && details.Reason != reasonRemoteDisappeared:
				recommended = "sync_pull"
			case details.HasConflict && details.Reason == reasonRemoteDisappeared:
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
						Reason:            reasonRemoteLockPresent,
						Message:           fmt.Sprintf("remote push lock exists: %s (another sync push may be in progress)", lockInfo.LockPath),
						RecommendedAction: "wait_or_sync_unlock",
					})
				}
			}

			if jsonOut {
				return json.NewEncoder(cmd.OutOrStdout()).Encode(res)
			}
			if terse {
				reason := res.Reason
				if strings.TrimSpace(reason) == "" {
					reason = "none"
				}
				blockers := "-"
				if len(res.Blockers) > 0 {
					blockers = strings.Join(res.Blockers, ",")
				}
				fmt.Fprintf(
					cmd.OutOrStdout(),
					"remote=%s has_conflict=%t reason=%s has_lock=%t blockers=%s recommended_action=%s\n",
					res.Remote, res.HasConflict, reason, res.HasLock, blockers, res.RecommendedAction,
				)
				return nil
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
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&terse, "terse", false, "human output: emit a single-line summary")
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
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
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
							Reason:   reasonLockMissing,
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
							Reason:   reasonLockMissing,
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
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
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
	cmd.Flags().StringVar(&vaultPath, "vault", "", "vault path to restore into (defaults to $KIMEN_VAULT, config.vault.path, or user config dir)")
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
							Reason:            reasonRemoteLockPresent,
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
							Reason:            reasonRemoteLockPresent,
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
			baselineHashes := map[string]string(nil)
			if pp, ok := resolvePassphraseIfConfigured(); ok {
				defer vault.Burn(pp)
				snap, err := loadLocalVaultSnapshot(vaultPath, pp, false)
				if err == nil {
					baselineHashes = snap.Hashes
				}
			}

			if c.Sync == nil {
				c.Sync = make(map[string]syncConfig)
			}
			c.Sync[remote.Name] = syncConfig{
				LastSeenRev:          newRev,
				LastLocalRev:         localRev,
				BaselineSecretHashes: baselineHashes,
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
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
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
	var reconcile bool
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

			pp := []byte(nil)
			localSnap := syncVaultSnapshot{}
			remoteSnap := syncVaultSnapshot{}
			reconcileAnalysis := syncChangeAnalysis{}
			if reconcile && hasLocal {
				pp, err = resolvePassphraseForVault(vaultPath, "", false)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				defer vault.Burn(pp)
				localSnap, err = loadLocalVaultSnapshot(vaultPath, pp, true)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				remoteSnap, err = loadRemoteVaultSnapshotFromBundlePath(bundlePath, remote.Identity, pp, true)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				syncState := c.Sync[remote.Name]
				reconcileAnalysis = analyzeSyncChanges(syncState.BaselineSecretHashes, localSnap.Hashes, remoteSnap.Hashes)
				if !reconcileAnalysis.HasBaseline {
					return syncCommandError(cmd, jsonOut, &syncConditionError{
						Reason:            reasonReconcileBaselineMissing,
						Message:           "cannot reconcile without baseline key hashes; run `kimen sync pull` first",
						RecommendedAction: "sync_pull",
					})
				}
				if len(reconcileAnalysis.ConflictKeys) > 0 {
					return syncCommandError(cmd, jsonOut, newSyncOverlappingChangesConflict(reconcileAnalysis.ConflictKeys))
				}
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
						Reconcile:   reconcile,
						CanReconcile: !reconcile ||
							(reconcile && (!hasLocal || (reconcileAnalysis.HasBaseline && len(reconcileAnalysis.ConflictKeys) == 0))),
						ConflictKeys: reconcileAnalysis.ConflictKeys,
					})
				}
				fmt.Fprintf(cmd.OutOrStdout(), "dry-run: pull %s (rev=%s)\n", remote.Name, remoteRev)
				fmt.Fprintf(cmd.OutOrStdout(), "would-backup: %t\n", wouldBackup)
				fmt.Fprintf(cmd.OutOrStdout(), "in-sync: %t\n", inSync)
				if reconcile {
					fmt.Fprintf(cmd.OutOrStdout(), "reconcile: true (conflicts=%d)\n", len(reconcileAnalysis.ConflictKeys))
				}
				return nil
			}

			backupPath := ""
			if !noBackup && hasLocal {
				backupPath, err = backupExistingVault(vaultPath)
				if err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
			}

			mergedKeyCount := 0
			baselineHashes := map[string]string(nil)
			if reconcile && hasLocal {
				if err := applyReconcileMerge(vaultPath, pp, localSnap, remoteSnap, reconcileAnalysis); err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				mergedKeyCount = len(reconcileAnalysis.LocalOnlyChangedKeys) + len(reconcileAnalysis.RemoteOnlyChangedKeys)
				baselineHashes = copyStringMap(remoteSnap.Hashes)
			} else {
				if err := bundle.OpenToVaultFile(bundlePath, vaultPath, id, true); err != nil {
					return syncCommandError(cmd, jsonOut, err)
				}
				if ppOpt, ok := resolvePassphraseIfConfigured(); ok {
					defer vault.Burn(ppOpt)
					snap, err := loadLocalVaultSnapshot(vaultPath, ppOpt, false)
					if err == nil {
						baselineHashes = snap.Hashes
					}
				}
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
				LastSeenRev:          remoteRev,
				LastLocalRev:         localRev,
				BaselineSecretHashes: baselineHashes,
			}
			if _, err := saveConfig(c); err != nil {
				return syncCommandError(cmd, jsonOut, err)
			}

			if jsonOut {
				action := "sync_pull"
				if reconcile {
					action = "sync_pull_reconcile"
				}
				return json.NewEncoder(cmd.OutOrStdout()).Encode(syncResult{
					OK:             true,
					Action:         action,
					Remote:         remote.Name,
					RemoteType:     remote.Type,
					RemotePath:     remote.Path,
					BundlePath:     bundleRef,
					VaultPath:      vaultPath,
					RemoteRev:      remoteRev,
					LastSeenRev:    remoteRev,
					BackupPath:     backupPath,
					HasRemote:      true,
					HasLocal:       true,
					InSync:         true,
					Reconcile:      reconcile,
					MergedKeyCount: mergedKeyCount,
				})
			}
			if reconcile {
				fmt.Fprintf(cmd.OutOrStdout(), "reconciled pull %s (rev=%s)\n", remote.Name, remoteRev)
				fmt.Fprintf(cmd.OutOrStdout(), "merged-keys: %d\n", mergedKeyCount)
			} else {
				fmt.Fprintf(cmd.OutOrStdout(), "pulled %s (rev=%s)\n", remote.Name, remoteRev)
			}
			if backupPath != "" {
				fmt.Fprintf(cmd.OutOrStdout(), "backup: %s\n", backupPath)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&remoteName, "remote", "", syncRemoteFlagHelp)
	cmd.Flags().BoolVar(&jsonOut, "json", false, "output JSON")
	cmd.Flags().BoolVar(&noBackup, "no-backup", false, "skip creating a local vault backup before overwrite")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "validate pull preconditions without modifying local vault/baseline")
	cmd.Flags().BoolVar(&reconcile, "reconcile", false, "merge disjoint local/remote key changes instead of replacing local vault")
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

func buildSyncPreflightPullReconcileArgs(remoteName string) []string {
	args := []string{"sync", "pull", "--dry-run", "--reconcile", "--json"}
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

func buildSyncAutoPullReconcileArgs(remoteName string) []string {
	args := []string{"sync", "pull", "--reconcile", "--json"}
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
			if localChangeUncertain {
				if conflict.HasConflict {
					return "blocked", &syncConflictError{Details: conflict}
				}
				return "blocked", &syncConditionError{
					Reason:            reasonManualPullRequired,
					Message:           "remote pull required but local vault has unpushed changes; run `kimen sync pull` manually and re-apply local changes",
					RecommendedAction: "sync_pull",
				}
			}
			if conflict.HasConflict && conflict.Reason != reasonRemoteChanged {
				return "blocked", &syncConflictError{Details: conflict}
			}
			return "pull_reconcile", nil
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
			Reason:            reasonRemoteLockPresent,
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

func renderSyncAutoAndExit(cmd *cobra.Command, result syncAutoResult, jsonOut, terse bool) error {
	if result.ExitCode == 0 && !result.OK {
		result.ExitCode = exitcode.CodeSyncFailed
	}
	if jsonOut {
		if err := json.NewEncoder(cmd.OutOrStdout()).Encode(result); err != nil {
			return err
		}
	} else {
		renderSyncAutoHuman(cmd, result, terse)
	}
	if result.ExitCode != 0 {
		return exitcode.New(result.ExitCode, errors.New("sync failed"))
	}
	return nil
}

func renderSyncAutoHuman(cmd *cobra.Command, result syncAutoResult, terse bool) {
	if terse {
		remote := strings.TrimSpace(result.Remote)
		if remote == "" {
			remote = "-"
		}
		reason := strings.TrimSpace(result.Reason)
		if reason == "" {
			reason = "none"
		}
		recommended := strings.TrimSpace(result.RecommendedAction)
		if recommended == "" {
			recommended = "none"
		}
		fmt.Fprintf(
			cmd.OutOrStdout(),
			"remote=%s decision=%s ok=%t exit_code=%d reason=%s recommended_action=%s\n",
			remote, result.Decision, result.OK, result.ExitCode, reason, recommended,
		)
		return
	}
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

func copyStringMap(in map[string]string) map[string]string {
	if len(in) == 0 {
		return nil
	}
	out := make(map[string]string, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

func syncRemoteEndpointSignature(r remoteConfig) string {
	rr := r
	applyRemoteDefaults(&rr)
	if remoteType(rr) == "git" {
		return fmt.Sprintf(
			"git|%s|%s|%s",
			strings.TrimSpace(rr.Path),
			strings.TrimSpace(rr.Branch),
			strings.TrimSpace(rr.BundlePath),
		)
	}
	return fmt.Sprintf("fs|%s", strings.TrimSpace(rr.Path))
}

func syncInitNextCommand(remoteName, recommendedAction string) string {
	name := strings.TrimSpace(remoteName)
	switch strings.TrimSpace(recommendedAction) {
	case "sync_pull":
		return fmt.Sprintf("kimen sync pull --remote %s", name)
	case "sync_push":
		return fmt.Sprintf("kimen sync push --remote %s", name)
	case "wait_or_sync_unlock":
		return fmt.Sprintf("kimen sync unlock --remote %s --yes", name)
	case "configure_remote_recipient":
		return fmt.Sprintf("kimen remote set %s --recipient <age-recipient>", name)
	case "configure_remote_identity":
		return fmt.Sprintf("kimen remote set %s --identity <path-to-age-identity>", name)
	case "sync_reset_baseline_or_remote_recreate":
		return fmt.Sprintf("kimen sync reset-baseline --remote %s --to-remote --yes", name)
	case "vault_init":
		return "kimen vault init"
	default:
		return ""
	}
}

func resolvePassphraseIfConfigured() ([]byte, bool) {
	if p := os.Getenv(envPassphrase); p != "" {
		return []byte(p), true
	}
	c, _, err := loadConfig()
	if err != nil || c.Unlock == nil {
		return nil, false
	}
	switch strings.ToLower(strings.TrimSpace(c.Unlock.Method)) {
	case "exec":
		if len(c.Unlock.Exec) == 0 {
			return nil, false
		}
		pp, err := resolvePassphraseFromExec(c.Unlock.Exec)
		if err != nil {
			return nil, false
		}
		return pp, true
	default:
		return nil, false
	}
}

func loadLocalVaultSnapshot(vaultPath string, passphrase []byte, includeSecrets bool) (syncVaultSnapshot, error) {
	return loadVaultSnapshot(vaultPath, passphrase, includeSecrets)
}

func loadRemoteVaultSnapshot(remote remoteConfig, passphrase []byte, includeSecrets bool) (syncVaultSnapshot, error) {
	bundlePath, cleanup, err := materializeRemoteBundleForRead(remote)
	if err != nil {
		return syncVaultSnapshot{}, err
	}
	defer cleanup()
	return loadRemoteVaultSnapshotFromBundlePath(bundlePath, strings.TrimSpace(remote.Identity), passphrase, includeSecrets)
}

func loadRemoteVaultSnapshotFromBundlePath(bundlePath, identityPath string, passphrase []byte, includeSecrets bool) (syncVaultSnapshot, error) {
	id, err := bundle.LoadIdentity(identityPath, false, nil)
	if err != nil {
		return syncVaultSnapshot{}, err
	}
	tmpDir, err := os.MkdirTemp("", "kimen-sync-snapshot-*")
	if err != nil {
		return syncVaultSnapshot{}, err
	}
	defer os.RemoveAll(tmpDir)
	tmpVaultPath := filepath.Join(tmpDir, "vault.db")
	if err := bundle.OpenToVaultFile(bundlePath, tmpVaultPath, id, true); err != nil {
		return syncVaultSnapshot{}, err
	}
	return loadVaultSnapshot(tmpVaultPath, passphrase, includeSecrets)
}

func loadVaultSnapshot(vaultPath string, passphrase []byte, includeSecrets bool) (syncVaultSnapshot, error) {
	snapshot := syncVaultSnapshot{
		Hashes: make(map[string]string),
	}
	if includeSecrets {
		snapshot.Secrets = make(map[string]vault.Secret)
	}
	v, err := vault.Open(vaultPath, passphrase)
	if err != nil {
		return syncVaultSnapshot{}, err
	}
	defer v.Close()
	names, err := v.ListSecretNames(context.Background())
	if err != nil {
		return syncVaultSnapshot{}, err
	}
	sort.Strings(names)
	for _, name := range names {
		sec, err := v.GetSecret(context.Background(), name)
		if err != nil {
			return syncVaultSnapshot{}, err
		}
		hash := hashSyncSecret(sec)
		snapshot.Hashes[name] = hash
		if includeSecrets {
			valueCopy := append([]byte(nil), sec.Value...)
			snapshot.Secrets[name] = vault.Secret{
				Name:  sec.Name,
				Type:  sec.Type,
				Value: valueCopy,
			}
		}
		vault.Burn(sec.Value)
	}
	return snapshot, nil
}

func hashSyncSecret(sec vault.Secret) string {
	sum := sha256.New()
	_, _ = sum.Write([]byte(sec.Type))
	_, _ = sum.Write([]byte{0})
	_, _ = sum.Write(sec.Value)
	return fmt.Sprintf("%x", sum.Sum(nil))
}

func analyzeSyncChanges(baseline, local, remote map[string]string) syncChangeAnalysis {
	analysis := syncChangeAnalysis{
		HasBaseline: baseline != nil,
		LocalOps:    make(map[string]syncChangeOp),
		RemoteOps:   make(map[string]syncChangeOp),
	}
	if !analysis.HasBaseline {
		return analysis
	}
	analysis.LocalOps, analysis.LocalChangedKeys = computeSyncChangeOps(baseline, local)
	analysis.RemoteOps, analysis.RemoteChangedKeys = computeSyncChangeOps(baseline, remote)

	overlap := make([]string, 0)
	conflicts := make([]string, 0)
	localOnly := make([]string, 0)
	remoteOnly := make([]string, 0)

	keySet := make(map[string]struct{}, len(analysis.LocalOps)+len(analysis.RemoteOps))
	for k := range analysis.LocalOps {
		keySet[k] = struct{}{}
	}
	for k := range analysis.RemoteOps {
		keySet[k] = struct{}{}
	}
	keys := make([]string, 0, len(keySet))
	for k := range keySet {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	for _, key := range keys {
		lo, ok := analysis.LocalOps[key]
		if !ok {
			lo = syncChangeUnchanged
		}
		ro, ok := analysis.RemoteOps[key]
		if !ok {
			ro = syncChangeUnchanged
		}
		localChanged := lo != syncChangeUnchanged
		remoteChanged := ro != syncChangeUnchanged
		switch {
		case localChanged && remoteChanged:
			overlap = append(overlap, key)
			if isSyncConflictChange(lo, ro, local[key], remote[key]) {
				conflicts = append(conflicts, key)
			}
		case localChanged:
			localOnly = append(localOnly, key)
		case remoteChanged:
			remoteOnly = append(remoteOnly, key)
		}
	}
	analysis.OverlappingKeys = overlap
	analysis.ConflictKeys = conflicts
	analysis.LocalOnlyChangedKeys = localOnly
	analysis.RemoteOnlyChangedKeys = remoteOnly
	return analysis
}

func computeSyncChangeOps(baseline, current map[string]string) (map[string]syncChangeOp, []string) {
	ops := make(map[string]syncChangeOp)
	changed := make([]string, 0)
	keySet := make(map[string]struct{}, len(baseline)+len(current))
	for k := range baseline {
		keySet[k] = struct{}{}
	}
	for k := range current {
		keySet[k] = struct{}{}
	}
	keys := make([]string, 0, len(keySet))
	for k := range keySet {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, key := range keys {
		baseHash, inBase := baseline[key]
		curHash, inCur := current[key]
		switch {
		case !inBase && inCur:
			ops[key] = syncChangeAdded
		case inBase && !inCur:
			ops[key] = syncChangeRemoved
		case inBase && inCur && strings.TrimSpace(baseHash) != strings.TrimSpace(curHash):
			ops[key] = syncChangeModified
		default:
			ops[key] = syncChangeUnchanged
		}
		if ops[key] != syncChangeUnchanged {
			changed = append(changed, key)
		}
	}
	return ops, changed
}

func isSyncConflictChange(localOp, remoteOp syncChangeOp, localHash, remoteHash string) bool {
	switch {
	case localOp == syncChangeRemoved && remoteOp == syncChangeRemoved:
		return false
	case (localOp == syncChangeAdded || localOp == syncChangeModified) &&
		(remoteOp == syncChangeAdded || remoteOp == syncChangeModified):
		return strings.TrimSpace(localHash) != strings.TrimSpace(remoteHash)
	default:
		return true
	}
}

func diffSnapshotsCurrent(local, remote map[string]string) ([]string, []string, []string) {
	onlyLocal := make([]string, 0)
	onlyRemote := make([]string, 0)
	different := make([]string, 0)
	for key, localHash := range local {
		remoteHash, ok := remote[key]
		if !ok {
			onlyLocal = append(onlyLocal, key)
			continue
		}
		if strings.TrimSpace(localHash) != strings.TrimSpace(remoteHash) {
			different = append(different, key)
		}
	}
	for key := range remote {
		if _, ok := local[key]; !ok {
			onlyRemote = append(onlyRemote, key)
		}
	}
	sort.Strings(onlyLocal)
	sort.Strings(onlyRemote)
	sort.Strings(different)
	return onlyLocal, onlyRemote, different
}

func newSyncOverlappingChangesConflict(conflictKeys []string) error {
	keys := append([]string(nil), conflictKeys...)
	sort.Strings(keys)
	msg := "local and remote have overlapping key changes; manual reconciliation required"
	if len(keys) > 0 {
		msg = fmt.Sprintf("%s (keys=%s)", msg, strings.Join(keys, ","))
	}
	return &syncConflictError{
		Details: syncConflictDetails{
			HasConflict: true,
			Reason:      reasonOverlappingChanges,
			Message:     msg,
		},
	}
}

func resolveSyncResolveKeys(raw, conflictKeys []string) ([]string, error) {
	conflictSet := make(map[string]struct{}, len(conflictKeys))
	for _, key := range conflictKeys {
		if strings.TrimSpace(key) == "" {
			continue
		}
		conflictSet[key] = struct{}{}
	}
	selectedSet := make(map[string]struct{}, len(raw))
	selected := make([]string, 0, len(raw))
	for _, token := range raw {
		for _, part := range strings.Split(token, ",") {
			key := strings.TrimSpace(part)
			if key == "" {
				continue
			}
			if _, exists := selectedSet[key]; exists {
				continue
			}
			selectedSet[key] = struct{}{}
			selected = append(selected, key)
		}
	}
	if len(selected) == 0 {
		return nil, nil
	}
	sort.Strings(selected)
	invalid := make([]string, 0)
	for _, key := range selected {
		if _, ok := conflictSet[key]; !ok {
			invalid = append(invalid, key)
		}
	}
	if len(invalid) > 0 {
		return nil, fmt.Errorf("keys are not current conflict keys: %s", strings.Join(invalid, ","))
	}
	return selected, nil
}

func recommendedActionForSyncChangeAnalysis(analysis syncChangeAnalysis) string {
	switch {
	case !analysis.HasBaseline:
		return "sync_pull_or_sync_reset_baseline"
	case len(analysis.ConflictKeys) > 0:
		return "manual_reconcile"
	case len(analysis.RemoteChangedKeys) > 0 && len(analysis.LocalChangedKeys) == 0:
		return "sync_pull"
	case len(analysis.LocalChangedKeys) > 0 && len(analysis.RemoteChangedKeys) == 0:
		return "sync_push"
	case len(analysis.LocalChangedKeys) > 0 || len(analysis.RemoteChangedKeys) > 0:
		return "sync_pull_reconcile"
	default:
		return "none"
	}
}

func applyReconcileMerge(vaultPath string, passphrase []byte, localSnap, remoteSnap syncVaultSnapshot, analysis syncChangeAnalysis) error {
	v, err := vault.Open(vaultPath, passphrase)
	if err != nil {
		return err
	}
	defer v.Close()

	keySet := make(map[string]struct{}, len(analysis.LocalOps)+len(analysis.RemoteOps))
	for k := range analysis.LocalOps {
		keySet[k] = struct{}{}
	}
	for k := range analysis.RemoteOps {
		keySet[k] = struct{}{}
	}
	keys := make([]string, 0, len(keySet))
	for k := range keySet {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	for _, key := range keys {
		lo, ok := analysis.LocalOps[key]
		if !ok {
			lo = syncChangeUnchanged
		}
		ro, ok := analysis.RemoteOps[key]
		if !ok {
			ro = syncChangeUnchanged
		}
		switch {
		case lo == syncChangeUnchanged && ro == syncChangeUnchanged:
			continue
		case lo != syncChangeUnchanged && ro == syncChangeUnchanged:
			continue
		case lo == syncChangeUnchanged && ro != syncChangeUnchanged:
			if ro == syncChangeRemoved {
				if err := v.DeleteSecret(context.Background(), key); err != nil && !errors.Is(err, vault.ErrSecretNotFound) {
					return err
				}
				continue
			}
			remoteSecret, ok := remoteSnap.Secrets[key]
			if !ok {
				return fmt.Errorf("remote secret %q missing during reconcile", key)
			}
			if err := v.PutSecret(context.Background(), vault.Secret{
				Name:  key,
				Type:  remoteSecret.Type,
				Value: remoteSecret.Value,
			}); err != nil {
				return err
			}
		default:
			if lo == syncChangeRemoved && ro == syncChangeRemoved {
				if err := v.DeleteSecret(context.Background(), key); err != nil && !errors.Is(err, vault.ErrSecretNotFound) {
					return err
				}
				continue
			}
			if isSyncConflictChange(lo, ro, localSnap.Hashes[key], remoteSnap.Hashes[key]) {
				return newSyncOverlappingChangesConflict([]string{key})
			}
		}
	}
	return nil
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
			if strings.TrimSpace(resp.Reason) == "" {
				resp.Reason = syncErrorReason(err)
			}
		} else {
			resp.Reason = syncErrorReason(err)
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

func syncErrorReason(err error) string {
	if err == nil {
		return ""
	}
	msg := strings.ToLower(strings.TrimSpace(err.Error()))
	switch {
	case strings.Contains(msg, "--stale-threshold must be >= 0"):
		return reasonInvalidStaleThreshold
	case strings.Contains(msg, "--check and --dry-run cannot be used together"):
		return reasonConflictingCheckAndDryRun
	case strings.Contains(msg, "empty remote name"):
		return reasonEmptyRemoteName
	case strings.Contains(msg, "remote name mismatch between arg and --remote"):
		return reasonRemoteNameMismatch
	case strings.Contains(msg, "invalid remote name"):
		return reasonInvalidRemoteName
	case strings.Contains(msg, "remote ") && strings.Contains(msg, "already exists"):
		return reasonRemoteExists
	case strings.Contains(msg, "remote ") && strings.Contains(msg, "not found (from kimen_remote)"):
		return reasonRemoteNotFoundFromEnv
	case strings.Contains(msg, "remote ") && strings.Contains(msg, "not found"):
		return reasonRemoteNotFound
	case strings.Contains(msg, "no remotes configured"):
		return reasonNoRemotesConfigured
	case strings.Contains(msg, "multiple remotes configured"):
		return reasonMultipleRemotesConfigured
	case strings.Contains(msg, "--path is required"):
		return reasonMissingPath
	case strings.Contains(msg, "--branch/--bundle-path are only valid for --type git"):
		return reasonGitFieldsRequireGitType
	case strings.Contains(msg, "derive recipient from identity"):
		return reasonRecipientDerivationFailed
	case strings.Contains(msg, "--take must be one of"):
		return reasonInvalidTake
	case strings.Contains(msg, "choose exactly one mode: --to-remote, --clear, or --rev"):
		return reasonInvalidResetBaselineMode
	case strings.Contains(msg, "refusing to reset baseline without --yes"):
		return reasonResetBaselineConfirmationRequired
	case strings.Contains(msg, "remote bundle is missing; cannot set baseline to remote"):
		return reasonRemoteBundleMissingForBaseline
	case strings.Contains(msg, "--if-older-than must be >= 0"):
		return reasonInvalidIfOlderThan
	case strings.Contains(msg, "sync unlock is only supported for fs remotes"):
		return reasonUnlockRequiresFSRemote
	case strings.Contains(msg, "refusing to unlock") && strings.Contains(msg, "lock is only"):
		return reasonLockTooNew
	case strings.Contains(msg, "refusing to remove lock without --yes"):
		return reasonUnlockConfirmationRequired
	case strings.Contains(msg, "--backup is required"):
		return reasonMissingBackup
	case strings.Contains(msg, "--lock-wait must be >= 0"):
		return reasonInvalidLockWait
	case strings.Contains(msg, "--break-stale-lock-after must be >= 0"):
		return reasonInvalidBreakStaleLockAfter
	case strings.Contains(msg, "--dry-run cannot be combined with --lock-wait/--break-stale-lock-after"):
		return reasonConflictingDryRunLockFlags
	case strings.Contains(msg, "remote recipient is not configured"):
		return reasonRemoteRecipientMissing
	case strings.Contains(msg, "--lock-wait/--break-stale-lock-after are only supported for fs remotes"):
		return reasonLockFlagsRequireFSRemote
	case strings.Contains(msg, "local vault file not found"):
		return reasonLocalVaultMissing
	case strings.Contains(msg, "local vault missing after sync resolve"):
		return reasonLocalVaultMissingAfterResolve
	case strings.Contains(msg, "local vault missing after pull"):
		return reasonLocalVaultMissingAfterPull
	case strings.Contains(msg, "local vault disappeared before baseline update"):
		return reasonLocalVaultDisappearedBeforeBaseline
	case strings.Contains(msg, "remote identity is not configured"):
		return reasonRemoteIdentityMissing
	case strings.Contains(msg, "remote bundle is missing"):
		return reasonRemoteBundleMissing
	case strings.Contains(msg, "unsupported remote type"):
		return reasonUnsupportedRemoteType
	case strings.Contains(msg, "unknown preflight check"):
		return reasonUnknownPreflightCheck
	case strings.Contains(msg, "unsupported preflight check"):
		return reasonUnsupportedPreflightCheck
	case strings.Contains(msg, "no preflight checks selected"):
		return reasonNoPreflightChecksSelected
	case strings.Contains(msg, "keys are not current conflict keys"):
		return reasonResolveKeysNotConflicts
	case strings.Contains(msg, "sync status returned empty payload"):
		return reasonSyncStatusEmptyPayload
	case strings.Contains(msg, "decode sync status payload"):
		return reasonSyncStatusDecodeFailed
	case strings.Contains(msg, "sync status payload missing action"):
		return reasonSyncStatusMissingAction
	default:
		return reasonSyncFailed
	}
}

func recommendedActionForConflictReason(reason string) string {
	switch strings.TrimSpace(reason) {
	case reasonRemoteChanged, reasonNoLocalBaseline:
		return "sync_pull"
	case reasonRemoteDisappeared:
		return "sync_reset_baseline_or_remote_recreate"
	case reasonOverlappingChanges:
		return "manual_reconcile"
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
