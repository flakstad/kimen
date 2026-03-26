package cli

// Machine-readable reason codes used in JSON envelopes.
const (
	// Shared typed reasons.
	reasonSecretNotFound  = "secret_not_found"
	reasonSecretExists    = "secret_exists"
	reasonVaultNotFound   = "vault_not_found"
	reasonWrongPassphrase = "wrong_passphrase"

	// Secret command reasons.
	reasonEmptySecretName      = "empty_secret_name"
	reasonSameSecretName       = "same_secret_name"
	reasonUnsafeStdoutRequired = "unsafe_stdout_required"
	reasonEmptySecretValue     = "empty_secret_value"
	reasonMissingSecretValue   = "missing_secret_value"
	reasonSecretFailed         = "secret_failed"

	// Passphrase/config path helper reasons.
	reasonEmptyPassphraseCommand       = "empty_passphrase_command"
	reasonPassphraseCommandFailed      = "passphrase_command_failed"
	reasonMissingPassphrase            = "missing_passphrase"
	reasonConflictingPassphraseSources = "conflicting_passphrase_sources"
	reasonMissingNewPassphrase         = "missing_new_passphrase"
	reasonEmptyNewPassphrase           = "empty_new_passphrase"
	reasonNewPassphraseMismatch        = "new_passphrase_mismatch"
	reasonNewPassphraseUnchanged       = "new_passphrase_unchanged"
	reasonConfigPathUnavailable        = "config_path_unavailable"

	// Vault command reasons.
	reasonInvalidVaultFile         = "invalid_vault_file"
	reasonVaultExists              = "vault_exists"
	reasonConflictingBackupOptions = "conflicting_backup_options"
	reasonVaultFailed              = "vault_failed"

	// Bundle command reasons.
	reasonMissingOut              = "missing_out"
	reasonMissingIn               = "missing_in"
	reasonMissingRecipient        = "missing_recipient"
	reasonMissingIdentityInput    = "missing_identity_input"
	reasonInputMissing            = "input_missing"
	reasonIdentityExists          = "identity_exists"
	reasonOutputVaultExists       = "output_vault_exists"
	reasonInvalidRecipient        = "invalid_recipient"
	reasonMissingIdentityFile     = "missing_identity_file"
	reasonNoIdentityFound         = "no_identity_found"
	reasonMultipleIdentitiesFound = "multiple_identities_found"
	reasonBundleFailed            = "bundle_failed"

	// Config command reasons.
	reasonUnknownUnlockMethod      = "unknown_unlock_method"
	reasonMissingUnlockExecCommand = "missing_unlock_exec_command"
	reasonInvalidConfigJSON        = "invalid_config_json"
	reasonConfigFailed             = "config_failed"

	// Remote command reasons.
	reasonEmptyRemoteName                   = "empty_remote_name"
	reasonInvalidRemoteName                 = "invalid_remote_name"
	reasonRemoteNotFound                    = "remote_not_found"
	reasonRemoteExists                      = "remote_exists"
	reasonUnsupportedRemoteType             = "unsupported_remote_type"
	reasonMissingRemoteSetFields            = "missing_remote_set_fields"
	reasonConflictingDeriveFlags            = "conflicting_derive_flags"
	reasonConflictingDeriveRecipientInputs  = "conflicting_derive_recipient_inputs"
	reasonMissingIdentityForRecipientDerive = "missing_identity_for_recipient_derivation"
	reasonRecipientDerivationFailed         = "recipient_derivation_failed"
	reasonGitFieldsRequireGitType           = "git_fields_require_git_type"
	reasonMissingPath                       = "missing_path"
	reasonEmptyPath                         = "empty_path"
	reasonRemoteFailed                      = "remote_failed"

	// Init command reasons.
	reasonInvalidRemoteType = "invalid_remote_type"
	reasonOutputIsDirectory = "output_is_directory"
	reasonOutputExists      = "output_exists"
	reasonInitFailed        = "init_failed"

	// Projection/run/render reasons.
	reasonMissingCommand                = "missing_command"
	reasonConflictingMapProfileInputs   = "conflicting_map_profile_inputs"
	reasonInvalidProfileName            = "invalid_profile_name"
	reasonConflictingStdinInputs        = "conflicting_stdin_inputs"
	reasonConflictingRenderTargetInputs = "conflicting_render_target_inputs"
	reasonMissingRenderTarget           = "missing_render_target"
	reasonSystemdHintsRequiresService   = "systemd_hints_requires_service"
	reasonMissingFileMappings           = "missing_file_mappings"
	reasonInvalidSystemdService         = "invalid_systemd_service"
	reasonStdinNotSupported             = "stdin_not_supported"
	reasonNoFilesToRender               = "no_files_to_render"
	reasonEnvpathRequiresProjectedFiles = "envpath_requires_projected_files"
	reasonEnvpathMissingProjectedFile   = "envpath_missing_projected_file"
	reasonInvalidMapping                = "invalid_mapping"
	reasonInvalidEnvVar                 = "invalid_env_var"
	reasonInvalidRelativePath           = "invalid_relative_path"
	reasonProjectionFailed              = "projection_failed"

	// Plan reasons.
	reasonInvalidMode              = "invalid_mode"
	reasonConflictingAgainstInputs = "conflicting_against_inputs"
	reasonInvalidAgainstSpec       = "invalid_against_spec"
	reasonPlanFailed               = "plan_failed"

	// Envfile reasons.
	reasonMissingEnvMappings        = "missing_env_mappings"
	reasonMissingFilesDirForEnvpath = "missing_files_dir_for_envpath"
	reasonEnvfileFailed             = "envfile_failed"

	// Sync conflict/condition reasons.
	reasonRemoteDisappeared        = "remote_disappeared"
	reasonNoLocalBaseline          = "no_local_baseline"
	reasonRemoteChanged            = "remote_changed"
	reasonRemoteMissing            = "remote_missing"
	reasonLocalVaultMissing        = "local_vault_missing"
	reasonReconcileBaselineMissing = "reconcile_baseline_missing"
	reasonNoOverlappingConflicts   = "no_overlapping_conflicts"
	reasonResolveKeyNotConflict    = "resolve_key_not_conflict"
	reasonRemoteLockPresent        = "remote_lock_present"
	reasonRemoteRecipientMissing   = "remote_recipient_missing"
	reasonRemoteIdentityMissing    = "remote_identity_missing"
	reasonManualPullRequired       = "manual_pull_required"
	reasonPushBlocked              = "push_blocked"
	reasonLockMissing              = "lock_missing"
	reasonOverlappingChanges       = "overlapping_changes"

	// Sync validation/input reasons.
	reasonInvalidStaleThreshold               = "invalid_stale_threshold"
	reasonConflictingCheckAndDryRun           = "conflicting_check_and_dry_run"
	reasonRemoteNameMismatch                  = "remote_name_mismatch"
	reasonRemoteNotFoundFromEnv               = "remote_not_found_from_env"
	reasonNoRemotesConfigured                 = "no_remotes_configured"
	reasonMultipleRemotesConfigured           = "multiple_remotes_configured"
	reasonInvalidTake                         = "invalid_take"
	reasonInvalidResetBaselineMode            = "invalid_reset_baseline_mode"
	reasonResetBaselineConfirmationRequired   = "reset_baseline_confirmation_required"
	reasonRemoteBundleMissingForBaseline      = "remote_bundle_missing_for_baseline"
	reasonInvalidIfOlderThan                  = "invalid_if_older_than"
	reasonUnlockRequiresFSRemote              = "unlock_requires_fs_remote"
	reasonLockTooNew                          = "lock_too_new"
	reasonUnlockConfirmationRequired          = "unlock_confirmation_required"
	reasonMissingBackup                       = "missing_backup"
	reasonInvalidLockWait                     = "invalid_lock_wait"
	reasonInvalidBreakStaleLockAfter          = "invalid_break_stale_lock_after"
	reasonConflictingDryRunLockFlags          = "conflicting_dry_run_lock_flags"
	reasonLockFlagsRequireFSRemote            = "lock_flags_require_fs_remote"
	reasonLocalVaultMissingAfterResolve       = "local_vault_missing_after_resolve"
	reasonLocalVaultMissingAfterPull          = "local_vault_missing_after_pull"
	reasonLocalVaultDisappearedBeforeBaseline = "local_vault_disappeared_before_baseline_update"
	reasonRemoteBundleMissing                 = "remote_bundle_missing"
	reasonUnknownPreflightCheck               = "unknown_preflight_check"
	reasonUnsupportedPreflightCheck           = "unsupported_preflight_check"
	reasonNoPreflightChecksSelected           = "no_preflight_checks_selected"
	reasonResolveKeysNotConflicts             = "resolve_keys_not_conflicts"
	reasonSyncStatusEmptyPayload              = "sync_status_empty_payload"
	reasonSyncStatusDecodeFailed              = "sync_status_decode_failed"
	reasonSyncStatusMissingAction             = "sync_status_missing_action"
	reasonSyncFailed                          = "sync_failed"
)

func allReasonCodes() []string {
	return []string{
		reasonSecretNotFound,
		reasonSecretExists,
		reasonVaultNotFound,
		reasonWrongPassphrase,
		reasonEmptySecretName,
		reasonSameSecretName,
		reasonUnsafeStdoutRequired,
		reasonEmptySecretValue,
		reasonMissingSecretValue,
		reasonSecretFailed,
		reasonEmptyPassphraseCommand,
		reasonPassphraseCommandFailed,
		reasonMissingPassphrase,
		reasonConflictingPassphraseSources,
		reasonMissingNewPassphrase,
		reasonEmptyNewPassphrase,
		reasonNewPassphraseMismatch,
		reasonNewPassphraseUnchanged,
		reasonConfigPathUnavailable,
		reasonInvalidVaultFile,
		reasonVaultExists,
		reasonConflictingBackupOptions,
		reasonVaultFailed,
		reasonMissingOut,
		reasonMissingIn,
		reasonMissingRecipient,
		reasonMissingIdentityInput,
		reasonInputMissing,
		reasonIdentityExists,
		reasonOutputVaultExists,
		reasonInvalidRecipient,
		reasonMissingIdentityFile,
		reasonNoIdentityFound,
		reasonMultipleIdentitiesFound,
		reasonBundleFailed,
		reasonUnknownUnlockMethod,
		reasonMissingUnlockExecCommand,
		reasonInvalidConfigJSON,
		reasonConfigFailed,
		reasonEmptyRemoteName,
		reasonInvalidRemoteName,
		reasonRemoteNotFound,
		reasonRemoteExists,
		reasonUnsupportedRemoteType,
		reasonMissingRemoteSetFields,
		reasonConflictingDeriveFlags,
		reasonConflictingDeriveRecipientInputs,
		reasonMissingIdentityForRecipientDerive,
		reasonRecipientDerivationFailed,
		reasonGitFieldsRequireGitType,
		reasonMissingPath,
		reasonEmptyPath,
		reasonRemoteFailed,
		reasonInvalidRemoteType,
		reasonOutputIsDirectory,
		reasonOutputExists,
		reasonInitFailed,
		reasonMissingCommand,
		reasonConflictingMapProfileInputs,
		reasonInvalidProfileName,
		reasonConflictingStdinInputs,
		reasonConflictingRenderTargetInputs,
		reasonMissingRenderTarget,
		reasonSystemdHintsRequiresService,
		reasonMissingFileMappings,
		reasonInvalidSystemdService,
		reasonStdinNotSupported,
		reasonNoFilesToRender,
		reasonEnvpathRequiresProjectedFiles,
		reasonEnvpathMissingProjectedFile,
		reasonInvalidMapping,
		reasonInvalidEnvVar,
		reasonInvalidRelativePath,
		reasonProjectionFailed,
		reasonInvalidMode,
		reasonConflictingAgainstInputs,
		reasonInvalidAgainstSpec,
		reasonPlanFailed,
		reasonMissingEnvMappings,
		reasonMissingFilesDirForEnvpath,
		reasonEnvfileFailed,
		reasonRemoteDisappeared,
		reasonNoLocalBaseline,
		reasonRemoteChanged,
		reasonRemoteMissing,
		reasonLocalVaultMissing,
		reasonReconcileBaselineMissing,
		reasonNoOverlappingConflicts,
		reasonResolveKeyNotConflict,
		reasonRemoteLockPresent,
		reasonRemoteRecipientMissing,
		reasonRemoteIdentityMissing,
		reasonManualPullRequired,
		reasonPushBlocked,
		reasonLockMissing,
		reasonOverlappingChanges,
		reasonInvalidStaleThreshold,
		reasonConflictingCheckAndDryRun,
		reasonRemoteNameMismatch,
		reasonRemoteNotFoundFromEnv,
		reasonNoRemotesConfigured,
		reasonMultipleRemotesConfigured,
		reasonInvalidTake,
		reasonInvalidResetBaselineMode,
		reasonResetBaselineConfirmationRequired,
		reasonRemoteBundleMissingForBaseline,
		reasonInvalidIfOlderThan,
		reasonUnlockRequiresFSRemote,
		reasonLockTooNew,
		reasonUnlockConfirmationRequired,
		reasonMissingBackup,
		reasonInvalidLockWait,
		reasonInvalidBreakStaleLockAfter,
		reasonConflictingDryRunLockFlags,
		reasonLockFlagsRequireFSRemote,
		reasonLocalVaultMissingAfterResolve,
		reasonLocalVaultMissingAfterPull,
		reasonLocalVaultDisappearedBeforeBaseline,
		reasonRemoteBundleMissing,
		reasonUnknownPreflightCheck,
		reasonUnsupportedPreflightCheck,
		reasonNoPreflightChecksSelected,
		reasonResolveKeysNotConflicts,
		reasonSyncStatusEmptyPayload,
		reasonSyncStatusDecodeFailed,
		reasonSyncStatusMissingAction,
		reasonSyncFailed,
	}
}
