# Automation Contract

This document is the canonical machine-facing contract for:

- `--json` outputs
- error envelopes
- typed exit codes

Use this as the source of truth for CI/CD integrations.

## Output channel rules

- Success JSON is emitted on `stdout`.
- Error JSON envelopes are emitted on `stderr`, except:
  - `map lint --json` (report on `stdout`)
  - `doctor --json` (report on `stdout`)
  - `sync preflight --json` (report on `stdout`)
- Human-mode messages are emitted when `--json` is not set.

## Error envelope

Commands that use a standard error envelope emit:

```json
{
  "ok": false,
  "error": "human-readable message",
  "exit_code": 23
}
```

This shape is used by `secret`, `vault`, `bundle`, `config`, `remote`, `sync`, `plan`, `envfile`, `run`, `render`, and `init` (when `--json` is set).

## Command JSON shapes

General rule:

- Success JSON payloads that include top-level `ok` also include `exit_code: 0`.
- Commands that emit non-envelope/raw JSON (for example `config show`) are exceptions.

`secret --json`:

- success: `{"ok":true,"action":"set|list|get|rm|mv","exit_code":0,...}`
- error: standard error envelope on `stderr`

`vault --json`:

- success: `{"ok":true,"action":"vault_init|vault_info","exit_code":0,...}`
- error: standard error envelope on `stderr`

`bundle --json`:

- success: `{"ok":true,"action":"bundle_keygen|bundle_recipient|bundle_seal|bundle_open","exit_code":0,...}`
- error: standard error envelope on `stderr`

`config`:

- `config show` always emits config JSON on success
- `config path --json`, `config unlock set/show/clear --json` emit `{"ok":true,"action":...,"exit_code":0,...}`
- errors use standard error envelope on `stderr`

`remote --json`:

- success (`add|get|set|list|rm`): `{"ok":true,"action":"remote_add|remote_get|remote_set|remote_list|remote_rm","exit_code":0,...}`
- remote objects support:
  - `type=fs`: `path` is a directory/`.age` file path
  - `type=git`: `path` is repo URL/path, with `branch` and `bundle_path`
- `remote set` may include `baseline_reset=true` when endpoint fields changed (`--type`/`--path`/`--branch`/`--bundle-path`)
- error: standard error envelope on `stderr`

`sync --json`:

- `sync --json` (orchestration mode, no subcommand): emits report on `stdout` for both success and orchestration failures:
  - `{"ok":bool,"action":"sync","remote":"...","mode":"apply|dry_run|check","strict":bool,"dry_run":bool,"check":bool,"decision":"noop|push|pull|pull_reconcile|would_push|would_pull|would_pull_reconcile|blocked","exit_code":0|31|32|27|...,"local_changed":bool,"local_change_uncertain":bool,"reason":"...","recommended_action":"...","status":{...},"steps":[{"name":"doctor|sync_status|sync_push|sync_pull|sync_pull_reconcile|sync_push_dry_run|sync_pull_dry_run","command":"kimen ...","ok":bool,"exit_code":N,"error":"...","recommended_action":"...","payload":{...}}]}`
  - input/flag validation failures still use the standard error envelope on `stderr`
  - when `--remote` is omitted, remote selection is: `KIMEN_REMOTE` -> unique sync-state remote -> `origin` -> only configured remote -> error
- `sync init` success: `{"ok":true,"action":"sync_init","remote":"...","created":bool,"updated":bool,"derived_recipient":bool,"baseline_reset":bool,"check_skipped":bool,"check_ok":bool,"check_error":"...","recommended_action":"...","next_command":"...","remote_config":{...},"status":{...}}` (`status` omitted when check is skipped or fails)
- `sync status` success: `{"ok":true,"action":"sync_status","remote":"...","has_remote":bool,"has_lock":bool,"lock_blocks_push":bool,"lock_path":"...","lock_age":"...","lock_age_seconds":N,"likely_stale":bool,"lock_pid":"...","lock_host":"...","lock_user":"...","has_local":bool,"in_sync":bool,"can_push":bool,"needs_pull":bool,"blockers":["..."],"recommended_action":"sync_pull|sync_push|wait_or_sync_unlock|configure_remote_recipient|configure_remote_identity|sync_reset_baseline_or_remote_recreate|vault_init|none",...}`
- `sync changes` success: `{"ok":true,"action":"sync_changes","remote":"...","has_baseline":bool,"baseline_rev":"...","remote_rev":"...","has_remote":bool,"has_local":bool,"local_changed_keys":["..."],"remote_changed_keys":["..."],"overlapping_keys":["..."],"conflict_keys":["..."],"local_only_changed_keys":["..."],"remote_only_changed_keys":["..."],"current_only_local_keys":["..."],"current_only_remote_keys":["..."],"current_different_keys":["..."],"can_reconcile":bool,"recommended_action":"..."}` (requires passphrase)
- `sync resolve` success: `{"ok":true,"action":"sync_resolve","remote":"...","remote_rev":"...","last_seen_rev":"...","take":"local|remote","keys":["..."],"resolved_count":N,"remaining_conflict_keys":["..."],"remaining_conflict_count":N,"recommended_action":"manual_reconcile|sync_pull|sync_pull_reconcile|sync_push|none"}` (requires passphrase; updates baseline hashes for selected conflict keys)
- `sync preflight --json`: emits report on `stdout` for both success and failure:
  - `{"ok":bool,"action":"sync_preflight","remote":"...","strict":bool,"exit_code":0|31|32,"check_count":N,"failed_count":N,"failed_checks":["..."],"failed_check":"...","recommended_action":"...","checks":[{"name":"doctor|sync_status|sync_conflicts|sync_pull_dry_run|sync_push_dry_run","command":"kimen ...","ok":bool,"exit_code":N,"error":"...","recommended_action":"...","payload":{...}}]}`
  - strict mode runs doctor/status/conflicts with strict semantics
  - `--only`/`--skip` can restrict the executed checks (`doctor|status|conflicts|pull|push` aliases)
  - exit `31` when any strict sync conflict check fails; otherwise exit `32` when any check fails for non-conflict reasons
- `sync status --strict`: exits `31` for sync conflicts, exits `32` for non-conflict blockers (e.g. lock or missing config), otherwise succeeds with normal `sync_status` payload
- `sync conflicts` success: `{"ok":true,"action":"sync_conflicts","remote":"...","has_conflict":bool,"reason":"remote_changed|remote_disappeared|no_local_baseline","has_lock":bool,"lock_blocks_push":bool,"lock_path":"...","lock_age_seconds":N,"likely_stale":bool,"lock_pid":"...","lock_host":"...","blockers":["..."],"recommended_action":"sync_pull|wait_or_sync_unlock|sync_reset_baseline_or_remote_recreate|none",...}`
- `sync conflicts --strict`: exits `31` for sync conflicts, exits `32` for lock-only blockers, otherwise succeeds with normal `sync_conflicts` payload
- `sync reset-baseline` success: `{"ok":true,"action":"sync_reset_baseline","remote":"...","mode":"to_remote|clear|rev","previous_rev":"...","new_rev":"..."}`
- `sync unlock` success: `{"ok":true,"action":"sync_unlock","remote":"...","lock_path":"...","removed":bool,"reason":"lock_missing|..."}` (requires `--yes` when a lock exists)
- `sync restore` success: `{"ok":true,"action":"sync_restore","vault_path":"...","source_backup_path":"...","current_backup_path":"..."}`
- `sync push` success: `{"ok":true,"action":"sync_push","remote":"...","remote_rev":"...","stale_lock_broken":bool}` (`stale_lock_broken` omitted unless stale lock auto-break occurred)
- `sync push --dry-run` success: `{"ok":true,"action":"sync_push_dry_run","remote":"...","remote_rev":"...","last_seen_rev":"...","dry_run":true,"has_local":true,"can_push":true}` (no remote/config mutation)
- `sync pull` success: `{"ok":true,"action":"sync_pull","remote":"...","remote_rev":"...","in_sync":true,"backup_path":"..."}` (`backup_path` is omitted when there was no local vault to back up or when `--no-backup` is used)
- `sync pull --reconcile` success: `{"ok":true,"action":"sync_pull_reconcile","remote":"...","remote_rev":"...","reconcile":true,"merged_key_count":N,"backup_path":"..."}` (disjoint local/remote key changes merged; overlap fails)
- `sync pull --dry-run` success: `{"ok":true,"action":"sync_pull_dry_run","remote":"...","remote_rev":"...","dry_run":true,"has_local":bool,"would_backup":bool,"in_sync":bool}` (no local vault/config mutation)
- `sync push` uses a remote lock file (`<bundle>.lock`) for `type=fs`; lock contention failures use sync exit `32`
- `sync push --dry-run` rejects `--lock-wait` and `--break-stale-lock-after`
- for `type=git`, lock-related fields are false/empty and lock flags (`--lock-wait`, `--break-stale-lock-after`) are rejected
- sync conflict errors (exit `31`) include structured fields in the standard envelope:
  - `reason`: `remote_changed|remote_disappeared|no_local_baseline|overlapping_changes`
  - `expected_rev` / `actual_rev` when available
  - `recommended_action`: `sync_pull|sync_reset_baseline_or_remote_recreate|manual_reconcile`
- sync precondition errors (exit `32`) may include structured fields:
  - `reason`: e.g. `remote_lock_present|remote_missing|local_vault_missing|reconcile_baseline_missing|no_overlapping_conflicts|resolve_key_not_conflict`
  - `recommended_action`: e.g. `wait_or_sync_unlock|sync_pull|manual_reconcile|sync_reset_baseline_or_remote_recreate|none`
- error: standard error envelope on `stderr` (except `sync --json` orchestration failures and `sync preflight --json`, which report failures on `stdout`)

`plan --json` and `project plan --json`:

- success: `{"ok":true,"action":"plan","exit_code":0,"mode":"run|render|envfile",...}`
  - includes `command`, `env`, `files`, `stdin`, `env_paths`, optional `diff`
- error: standard error envelope on `stderr`

`envfile --json`:

- success: `{"ok":true,"action":"envfile","exit_code":0,"out":"...","count":N}`
- error: standard error envelope on `stderr`

`render --json`:

- success: `{"ok":true,"action":"render","exit_code":0,"out_dir":"...","file_count":N,...}`
- error: standard error envelope on `stderr`

`run --json`:

- success:
  - for `--dry-run`: emits plan payload JSON on `stdout` (`{"ok":true,"action":"plan","exit_code":0,...}`)
  - for normal execution: no success envelope (child process owns stdout/stderr)
- setup/projection errors: standard error envelope on `stderr`
- child command non-zero exit: forwarded child exit code

`map lint --json`:

- emits lint report on `stdout` in both success and failure cases:
  - `{"ok":true|false,"action":"map_lint","exit_code":0|20,"mode":"all|run|render|envfile","error_count":N,"warning_count":N,"issues":[...]}`
- no separate error envelope on `stderr`

`doctor --json`:

- success/failure report on `stdout`:
  - `{"ok":true|false,"action":"doctor","exit_code":0|27,"strict":bool,"error_count":N,"warning_count":N,"checks":[...]}`
- check entries include stable core checks (`config_*`, `passphrase_source`, `vault_*`, `mapping_*`, `bundle_*`) and per-remote checks like `remote_<name>_config|push|pull|transport|fs_dir|git_remote|git_branch|sync_state`
- no separate error envelope on `stderr`

`init --json`:

- `init ci-sync-gate` success: `{"ok":true,"action":"init_ci_sync_gate","exit_code":0,"out":"..."}`
- error: standard error envelope on `stderr`

`version --json`:

- success: `{"ok":true,"action":"version","exit_code":0,"version":"...","raw_version":"...","commit":"...","date":"..."}`
- error: generic command failure behavior

## Exit code matrix

- `12`: secret not found
- `13`: secret already exists
- `14`: vault not found
- `15`: wrong passphrase / corrupted vault
- `20`: map lint failed
- `21`: plan failed
- `22`: envfile failed
- `23`: projection failed
- `24`: vault command failed
- `25`: bundle command failed
- `26`: config command failed
- `27`: doctor failed
- `30`: remote command failed
- `31`: sync conflict (remote changed/deleted or baseline missing)
- `32`: sync command failed (non-conflict)
- `33`: init command failed

Notes:

- `run` forwards child process exit codes when the child starts and exits non-zero.
- `map lint --strict` treats warnings as failures (`20`).
- `doctor --strict` treats warnings as failures (`27`).
