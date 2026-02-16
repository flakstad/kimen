# Automation Contract

This document is the canonical machine-facing contract for:

- `--json` outputs
- error envelopes
- typed exit codes

Use this as the source of truth for CI/CD integrations.

## Output channel rules

- Success JSON is emitted on `stdout`.
- Error JSON envelopes are emitted on `stderr`, except `map lint --json` (see below).
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

This shape is used by `secret`, `vault`, `bundle`, `config`, `remote`, `sync`, `plan`, `envfile`, `run`, and `render` (when `--json` is set).

## Command JSON shapes

`secret --json`:

- success: `{"ok":true,"action":"set|list|get|rm|mv",...}`
- error: standard error envelope on `stderr`

`vault --json`:

- success: `{"ok":true,"action":"vault_init|vault_info",...}`
- error: standard error envelope on `stderr`

`bundle --json`:

- success: `{"ok":true,"action":"bundle_keygen|bundle_recipient|bundle_seal|bundle_open",...}`
- error: standard error envelope on `stderr`

`config`:

- `config show` always emits config JSON on success
- `config path --json`, `config unlock set/show/clear --json` emit `{"ok":true,"action":...}`
- errors use standard error envelope on `stderr`

`remote --json`:

- success (`add|get|set|list|rm`): `{"ok":true,"action":"remote_add|remote_get|remote_set|remote_list|remote_rm",...}`
- remote objects support:
  - `type=fs`: `path` is a directory/`.age` file path
  - `type=git`: `path` is repo URL/path, with `branch` and `bundle_path`
- `remote set` may include `baseline_reset=true` when endpoint fields changed (`--type`/`--path`/`--branch`/`--bundle-path`)
- error: standard error envelope on `stderr`

`sync --json`:

- `sync status` success: `{"ok":true,"action":"sync_status","remote":"...","has_remote":bool,"has_lock":bool,"lock_blocks_push":bool,"lock_path":"...","lock_age":"...","lock_age_seconds":N,"likely_stale":bool,"lock_pid":"...","lock_host":"...","lock_user":"...","has_local":bool,"in_sync":bool,"can_push":bool,"needs_pull":bool,"blockers":["..."],"recommended_action":"sync_pull|sync_push|wait_or_sync_unlock|configure_remote_recipient|configure_remote_identity|sync_reset_baseline_or_remote_recreate|vault_init|none",...}`
- `sync conflicts` success: `{"ok":true,"action":"sync_conflicts","remote":"...","has_conflict":bool,"reason":"remote_changed|remote_disappeared|no_local_baseline","has_lock":bool,"lock_blocks_push":bool,"lock_path":"...","lock_age_seconds":N,"likely_stale":bool,"lock_pid":"...","lock_host":"...","blockers":["..."],"recommended_action":"sync_pull|wait_or_sync_unlock|sync_reset_baseline_or_remote_recreate|none",...}`
- `sync reset-baseline` success: `{"ok":true,"action":"sync_reset_baseline","remote":"...","mode":"to_remote|clear|rev","previous_rev":"...","new_rev":"..."}`
- `sync unlock` success: `{"ok":true,"action":"sync_unlock","remote":"...","lock_path":"...","removed":bool,"reason":"lock_missing|..."}` (requires `--yes` when a lock exists)
- `sync restore` success: `{"ok":true,"action":"sync_restore","vault_path":"...","source_backup_path":"...","current_backup_path":"..."}`
- `sync push` success: `{"ok":true,"action":"sync_push","remote":"...","remote_rev":"...","stale_lock_broken":bool}` (`stale_lock_broken` omitted unless stale lock auto-break occurred)
- `sync pull` success: `{"ok":true,"action":"sync_pull","remote":"...","remote_rev":"...","in_sync":true,"backup_path":"..."}` (`backup_path` is omitted when there was no local vault to back up or when `--no-backup` is used)
- `sync pull --dry-run` success: `{"ok":true,"action":"sync_pull_dry_run","remote":"...","remote_rev":"...","dry_run":true,"has_local":bool,"would_backup":bool,"in_sync":bool}` (no local vault/config mutation)
- `sync push` uses a remote lock file (`<bundle>.lock`) for `type=fs`; lock contention failures use sync exit `32`
- for `type=git`, lock-related fields are false/empty and lock flags (`--lock-wait`, `--break-stale-lock-after`) are rejected
- sync conflict errors (exit `31`) include structured fields in the standard envelope:
  - `reason`: `remote_changed|remote_disappeared|no_local_baseline`
  - `expected_rev` / `actual_rev` when available
  - `recommended_action`: `sync_pull|sync_reset_baseline_or_remote_recreate`
- error: standard error envelope on `stderr`

`plan --json` and `project plan --json`:

- success: plan object (no top-level `ok` field), e.g.:
  - `mode`, `command`, `env`, `files`, `stdin`, `env_paths`, optional `diff`
- error: standard error envelope on `stderr`

`envfile --json`:

- success: `{"ok":true,"action":"envfile","out":"...","count":N}`
- error: standard error envelope on `stderr`

`render --json`:

- success: `{"ok":true,"action":"render","out_dir":"...","file_count":N,...}`
- error: standard error envelope on `stderr`

`run --json`:

- success:
  - for `--dry-run`: emits plan object JSON on `stdout`
  - for normal execution: no success envelope (child process owns stdout/stderr)
- setup/projection errors: standard error envelope on `stderr`
- child command non-zero exit: forwarded child exit code

`map lint --json`:

- emits lint report on `stdout` in both success and failure cases:
  - `{"ok":true|false,"error_count":N,"warning_count":N,"issues":[...]}`
- no separate error envelope on `stderr`

`doctor --json`:

- success/failure report on `stdout`:
  - `{"ok":true|false,"strict":bool,"error_count":N,"warning_count":N,"checks":[...]}`
- check entries include stable core checks (`config_*`, `passphrase_source`, `vault_*`, `mapping_*`, `bundle_*`) and per-remote checks like `remote_<name>_config|push|pull|transport|fs_dir|git_remote|git_branch`
- no separate error envelope on `stderr`

`version --json`:

- success: `{"version":"...","raw_version":"...","commit":"...","date":"..."}`
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

Notes:

- `run` forwards child process exit codes when the child starts and exits non-zero.
- `map lint --strict` treats warnings as failures (`20`).
- `doctor --strict` treats warnings as failures (`27`).
