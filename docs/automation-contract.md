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

- success (`add|list|rm`): `{"ok":true,"action":"remote_add|remote_list|remote_rm",...}`
- error: standard error envelope on `stderr`

`sync --json`:

- `sync status` success: `{"ok":true,"action":"sync_status","remote":"...","has_remote":bool,"in_sync":bool,"can_push":bool,"needs_pull":bool,...}`
- `sync push` success: `{"ok":true,"action":"sync_push","remote":"...","remote_rev":"..."}`
- `sync pull` success: `{"ok":true,"action":"sync_pull","remote":"...","remote_rev":"...","in_sync":true,"backup_path":"..."}` (`backup_path` is omitted when there was no local vault to back up or when `--no-backup` is used)
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
