(ns kimen.reason-codes)

(def reason-vault-not-found "vault_not_found")
(def reason-secret-not-found "secret_not_found")
(def reason-secret-exists "secret_exists")
(def reason-wrong-passphrase "wrong_passphrase")

(def reason-empty-secret-name "empty_secret_name")
(def reason-same-secret-name "same_secret_name")
(def reason-unsafe-stdout-required "unsafe_stdout_required")
(def reason-empty-secret-value "empty_secret_value")
(def reason-missing-secret-value "missing_secret_value")
(def reason-secret-failed "secret_failed")

(def reason-plan-failed "plan_failed")
(def reason-invalid-mode "invalid_mode")
(def reason-conflicting-map-profile-inputs "conflicting_map_profile_inputs")
(def reason-conflicting-against-inputs "conflicting_against_inputs")
(def reason-invalid-profile-name "invalid_profile_name")
(def reason-envpath-requires-projected-files "envpath_requires_projected_files")
(def reason-envpath-missing-projected-file "envpath_missing_projected_file")
(def reason-invalid-relative-path "invalid_relative_path")
(def reason-invalid-mapping "invalid_mapping")
(def reason-invalid-env-var "invalid_env_var")
(def reason-projection-failed "projection_failed")
(def reason-missing-command "missing_command")
(def reason-missing-file-mappings "missing_file_mappings")
(def reason-missing-render-target "missing_render_target")
(def reason-no-files-to-render "no_files_to_render")
(def reason-stdin-not-supported "stdin_not_supported")

(def reason-envfile-failed "envfile_failed")
(def reason-missing-out "missing_out")
(def reason-missing-in "missing_in")
(def reason-missing-recipient "missing_recipient")
(def reason-missing-identity-input "missing_identity_input")
(def reason-input-missing "input_missing")
(def reason-identity-exists "identity_exists")
(def reason-output-vault-exists "output_vault_exists")
(def reason-invalid-recipient "invalid_recipient")
(def reason-missing-identity-file "missing_identity_file")
(def reason-no-identity-found "no_identity_found")
(def reason-multiple-identities-found "multiple_identities_found")
(def reason-bundle-failed "bundle_failed")
(def reason-missing-env-mappings "missing_env_mappings")
(def reason-missing-files-dir-for-envpath "missing_files_dir_for_envpath")

(def reason-empty-passphrase-command "empty_passphrase_command")
(def reason-passphrase-command-failed "passphrase_command_failed")
(def reason-missing-passphrase "missing_passphrase")

(def reason-invalid-vault-file "invalid_vault_file")
(def reason-vault-exists "vault_exists")
(def reason-vault-failed "vault_failed")

(def reason-unknown-unlock-method "unknown_unlock_method")
(def reason-missing-unlock-exec-command "missing_unlock_exec_command")
(def reason-invalid-config-json "invalid_config_json")
(def reason-config-path-unavailable "config_path_unavailable")
(def reason-config-failed "config_failed")

(def reason-empty-remote-name "empty_remote_name")
(def reason-invalid-remote-name "invalid_remote_name")
(def reason-remote-not-found "remote_not_found")
(def reason-remote-not-found-from-env "remote_not_found_from_env")
(def reason-remote-exists "remote_exists")
(def reason-unsupported-remote-type "unsupported_remote_type")
(def reason-missing-remote-set-fields "missing_remote_set_fields")
(def reason-conflicting-derive-flags "conflicting_derive_flags")
(def reason-conflicting-derive-recipient-inputs "conflicting_derive_recipient_inputs")
(def reason-missing-identity-for-recipient-derivation "missing_identity_for_recipient_derivation")
(def reason-recipient-derivation-failed "recipient_derivation_failed")
(def reason-git-fields-require-git-type "git_fields_require_git_type")
(def reason-missing-path "missing_path")
(def reason-empty-path "empty_path")
(def reason-remote-failed "remote_failed")
(def reason-no-remotes-configured "no_remotes_configured")
(def reason-multiple-remotes-configured "multiple_remotes_configured")
(def reason-local-vault-missing "local_vault_missing")
(def reason-remote-bundle-missing "remote_bundle_missing")
(def reason-remote-recipient-missing "remote_recipient_missing")
(def reason-remote-identity-missing "remote_identity_missing")
(def reason-remote-lock-present "remote_lock_present")
(def reason-remote-disappeared "remote_disappeared")
(def reason-remote-changed "remote_changed")
(def reason-no-local-baseline "no_local_baseline")
(def reason-manual-pull-required "manual_pull_required")
(def reason-push-blocked "push_blocked")
(def reason-invalid-lock-wait "invalid_lock_wait")
(def reason-invalid-break-stale-lock-after "invalid_break_stale_lock_after")
(def reason-conflicting-dry-run-lock-flags "conflicting_dry_run_lock_flags")
(def reason-lock-flags-require-fs-remote "lock_flags_require_fs_remote")
(def reason-invalid-stale-threshold "invalid_stale_threshold")
(def reason-conflicting-check-and-dry-run "conflicting_check_and_dry_run")
(def reason-invalid-if-older-than "invalid_if_older_than")
(def reason-unlock-requires-fs-remote "unlock_requires_fs_remote")
(def reason-lock-too-new "lock_too_new")
(def reason-unlock-confirmation-required "unlock_confirmation_required")
(def reason-lock-missing "lock_missing")
(def reason-overlapping-changes "overlapping_changes")
(def reason-invalid-take "invalid_take")
(def reason-no-overlapping-conflicts "no_overlapping_conflicts")
(def reason-resolve-key-not-conflict "resolve_key_not_conflict")
(def reason-unknown-preflight-check "unknown_preflight_check")
(def reason-no-preflight-checks-selected "no_preflight_checks_selected")

(def reason-doctor-failed "doctor_failed")
(def reason-init-failed "init_failed")
(def reason-invalid-remote-type "invalid_remote_type")
(def reason-output-is-directory "output_is_directory")
(def reason-output-exists "output_exists")
(def reason-sync-failed "sync_failed")

(defn all-reason-codes
  []
  [reason-vault-not-found
   reason-secret-not-found
   reason-secret-exists
   reason-wrong-passphrase
   reason-empty-secret-name
   reason-same-secret-name
   reason-unsafe-stdout-required
   reason-empty-secret-value
   reason-missing-secret-value
   reason-secret-failed
   reason-plan-failed
   reason-invalid-mode
   reason-conflicting-map-profile-inputs
   reason-conflicting-against-inputs
   reason-invalid-profile-name
   reason-envpath-requires-projected-files
   reason-envpath-missing-projected-file
   reason-invalid-relative-path
   reason-invalid-mapping
   reason-invalid-env-var
   reason-projection-failed
   reason-missing-command
   reason-missing-file-mappings
   reason-missing-render-target
   reason-no-files-to-render
   reason-stdin-not-supported
   reason-envfile-failed
   reason-missing-out
   reason-missing-in
   reason-missing-recipient
   reason-missing-identity-input
   reason-input-missing
   reason-identity-exists
   reason-output-vault-exists
   reason-invalid-recipient
   reason-missing-identity-file
   reason-no-identity-found
   reason-multiple-identities-found
   reason-bundle-failed
   reason-missing-env-mappings
   reason-missing-files-dir-for-envpath
   reason-empty-passphrase-command
   reason-passphrase-command-failed
   reason-missing-passphrase
   reason-invalid-vault-file
   reason-vault-exists
   reason-vault-failed
   reason-unknown-unlock-method
   reason-missing-unlock-exec-command
   reason-invalid-config-json
   reason-config-path-unavailable
   reason-config-failed
   reason-empty-remote-name
   reason-invalid-remote-name
   reason-remote-not-found
   reason-remote-not-found-from-env
   reason-remote-exists
   reason-unsupported-remote-type
   reason-missing-remote-set-fields
   reason-conflicting-derive-flags
   reason-conflicting-derive-recipient-inputs
   reason-missing-identity-for-recipient-derivation
   reason-recipient-derivation-failed
   reason-git-fields-require-git-type
   reason-missing-path
   reason-empty-path
   reason-remote-failed
   reason-no-remotes-configured
   reason-multiple-remotes-configured
   reason-local-vault-missing
   reason-remote-bundle-missing
   reason-remote-recipient-missing
   reason-remote-identity-missing
   reason-remote-lock-present
   reason-remote-disappeared
   reason-remote-changed
   reason-no-local-baseline
   reason-manual-pull-required
   reason-push-blocked
   reason-invalid-lock-wait
   reason-invalid-break-stale-lock-after
   reason-conflicting-dry-run-lock-flags
   reason-lock-flags-require-fs-remote
   reason-invalid-stale-threshold
   reason-conflicting-check-and-dry-run
   reason-invalid-if-older-than
   reason-unlock-requires-fs-remote
   reason-lock-too-new
   reason-unlock-confirmation-required
   reason-lock-missing
   reason-overlapping-changes
   reason-invalid-take
   reason-no-overlapping-conflicts
   reason-resolve-key-not-conflict
   reason-unknown-preflight-check
   reason-no-preflight-checks-selected
   reason-doctor-failed
   reason-init-failed
   reason-invalid-remote-type
   reason-output-is-directory
   reason-output-exists
   reason-sync-failed])
