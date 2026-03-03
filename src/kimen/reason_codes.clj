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

(def reason-envfile-failed "envfile_failed")
(def reason-missing-out "missing_out")
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
   reason-envfile-failed
   reason-missing-out
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
   reason-config-failed])
