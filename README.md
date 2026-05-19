# Kimen

Kimen is a local-first secrets tool.

Apps can keep reading normal env vars and files. No need for complex config
libraries and precedence hierarchies. Kimen prepares the environment before the
app starts.

## Install

```bash
brew install flakstad/kimen/kimen
```

Or:

Download the latest release binaries for your platform from:

https://github.com/flakstad/kimen/releases/latest

Or:

```bash
make install
```

Or:

```bash
make build
./dist/kimen version
```

## Basic Use

Initialize a vault:

```bash
kimen vault init
```

Add a database URL. `prod.database_url` is the key in the vault, not the value.
Kimen prompts for the value in the terminal. It is not echoed, and it does not
go through shell history, an env var, or a repo file.

```bash
kimen secret set prod.database_url
```

Expose it to one command as a normal environment variable:

```bash
kimen run --env DATABASE_URL=prod.database_url -- ./your-app
```

The app reads `DATABASE_URL`. It does not need to know about Kimen.

## Profiles

For repeated commands, commit a profile to the repo.

Example: `.kimen/profiles/prod.kmap`

```text
env DATABASE_URL=prod.database_url
env SERVICE_API_TOKEN=prod.service_api_token
env PORT=const:5050
file credentials.json=prod.gcp_credentials_json
envpath GOOGLE_APPLICATION_CREDENTIALS=credentials.json
```

The names on the right are keys in the vault, except `const:5050`, which is a
literal value.

Run the profile:

```bash
kimen run --profile prod -- ./your-app
```

Kimen reads `.kimen/profiles/prod.kmap`, loads the referenced vault values, writes
any requested files into a temporary runtime directory, sets env vars, starts the
command, and cleans up afterwards.

`envpath` writes a file and sets the environment variable to the path of that
file. That is useful for tools that expect something like
`GOOGLE_APPLICATION_CREDENTIALS=/path/to/credentials.json`.

## Envfiles

Some deploy tools want an envfile instead of a wrapped command:

```bash
kimen envfile --profile prod --out .env.runtime
```

The envfile is generated from the vault and profile. It does not need to live in
the repo.

## Sessions

Unlock once for a short local window:

```bash
kimen session start --ttl 15m
```

Then scripts and tools running as the same user can use the vault without
prompting again until the TTL expires.

Lock it explicitly:

```bash
kimen session lock
```

## Notes

- Secrets are encrypted at rest in a local vault.
- Projections can produce env vars, files, envfiles, or one scoped command
  execution.
- Profiles are map files, usually committed under `.kimen/profiles/`.
- Values can come from `secret:<name>`, bare secret names, `const:<literal>`, or
  `exec:<command...>`.

Profile lookup order:

1. `$KIMEN_PROFILE_DIR/<name>.kmap`
2. `./.kimen/profiles/<name>.kmap`
3. `$XDG_CONFIG_HOME/kimen/profiles/<name>.kmap`
4. `$HOME/.config/kimen/profiles/<name>.kmap`

## CLI / API Reference

The CLI is the primary API.

Vault and config:

- `kimen version`
- `kimen config path|show`
- `kimen config vault set|show|clear`
- `kimen config unlock set|show|clear`
- `kimen vault init|info|path|rekey`
- `kimen session start|status|lock|stop`

Secrets:

- `kimen secret set <name> [--stdin|--value <text>]`
- `kimen secret list`
- `kimen secret get <name> --unsafe-stdout`
- `kimen secret rm <name>`
- `kimen secret mv <old-name> <new-name>`

Projection and planning:

- `kimen run`
- `kimen render`
- `kimen envfile`
- `kimen plan`
- `kimen map lint`
- `kimen doctor`
- `kimen project run|render|plan`

Bundles, remotes, and sync:

- `kimen bundle keygen|recipient|seal|open`
- `kimen remote add|get|set|list|rm`
- `kimen sync`
- `kimen sync init|preflight|status|conflicts|changes|push|pull|resolve|reset-baseline|unlock|restore`

Other:

- `kimen init ci-pr-safety|ci-deploy|ci-sync-gate`

Common conventions:

- vault precedence: `--vault` > `KIMEN_VAULT` > `config.vault.path` > default user config location
- passphrase sources: `KIMEN_PASSPHRASE`, `--passphrase-cmd`, `--passphrase-stdin`, active local session, configured unlock method, then TTY prompt
- machine-readable mode: `--json`

## Unlock Sessions

`kimen session` provides a short-lived, local unlock window for trusted same-user processes. It is useful when you want tools, scripts, or AI agents running as your user to use Kimen for a bounded period without receiving or re-entering the vault passphrase.

```bash
kimen session start --ttl 15m
kimen session status
kimen session lock
kimen session stop
```

`session start` verifies the passphrase against the selected vault and stores it only in a background daemon's memory. The daemon listens on a local Unix socket and keeps an idle TTL. Each successful reuse refreshes the expiry.

Commands that open the same vault reuse the session when no explicit passphrase source was provided:

```bash
kimen session start --ttl 15m
cd /some/other/project
kimen secret list --vault ~/.config/kimen/vault.db
kimen envfile --env API_KEY=api_key --out .env.runtime
kimen session lock
```

Session reuse is scoped by the canonical vault path, not by the current directory. If a command resolves to a different vault path, Kimen falls back to the normal passphrase sources.

Passphrase source precedence is:

1. `KIMEN_PASSPHRASE`
2. `--passphrase-cmd`
3. `--passphrase-stdin`
4. active local session for the same vault
5. configured unlock method
6. TTY prompt

`session status` reports whether the daemon is stopped, locked, or unlocked:

```bash
kimen session status
kimen session status --json
```

`session lock` forgets the in-memory passphrase but leaves the daemon running. `session stop` forgets the passphrase and shuts down the daemon.

The session socket is local to the user and stored under `$KIMEN_SESSION_DIR`, `$XDG_RUNTIME_DIR/kimen`, or the user cache directory with strict local permissions. Treat an active session as a deliberate grant: any local process running as your OS user can use Kimen commands against the unlocked vault until the session is locked, stopped, or expires. This is not a sandbox boundary between processes running as the same user.

## JSON Contract

Kimen is designed to be scriptable.

Rules:

- success JSON is emitted on `stdout`
- standard error envelopes are emitted on `stderr`
- `map lint --json`, `doctor --json`, and `sync preflight --json` emit reports on `stdout`

Standard error envelope:

```json
{
  "ok": false,
  "error": "human-readable message",
  "exit_code": 23,
  "reason": "machine_readable_reason"
}
```

General rule:

- success payloads that include `ok` also include `exit_code: 0`
- `config show` is a raw JSON config dump, not a standard envelope

Important success shapes:

- `secret --json`: `{"ok":true,"action":"set|list|get|rm|mv","exit_code":0,...}`
- `vault --json`: `{"ok":true,"action":"vault_init|vault_info|vault_path|vault_rekey","exit_code":0,...}`
- `bundle --json`: `{"ok":true,"action":"bundle_keygen|bundle_recipient|bundle_seal|bundle_open","exit_code":0,...}`
- `remote --json`: `{"ok":true,"action":"remote_add|remote_get|remote_set|remote_list|remote_rm","exit_code":0,...}`
- `sync --json`: orchestration report on `stdout`
- `sync preflight --json`: preflight report on `stdout`

Important reasons you can branch on include:

- `secret_not_found`
- `secret_exists`
- `vault_not_found`
- `wrong_passphrase`
- `invalid_vault_file`
- `remote_not_found`
- `remote_exists`
- `remote_lock_present`
- `remote_changed`
- `no_local_baseline`

Important typed exits:

- `0`: success
- `20`: map lint failure
- `31`: sync conflict / reconciliation required
- `32`: sync precondition failure

## Team Sync

Team Sync is optional. Runtime remains local even when sync is configured.

Shipped surface:

- `fs` and `git` remotes
- orchestration-first `kimen sync`
- explicit operator subcommands for status, conflicts, pull/push, resolve, unlock, and baseline reset
- ciphertext-only transport via `vault.age`
- strict CI gating with `kimen sync preflight --strict --json`

Recommended default operating model:

- one writer pushes
- others pull

Useful commands:

```bash
kimen sync preflight --remote team --strict --json
kimen sync conflicts --remote team --strict --json
kimen sync push --remote team
kimen sync pull --remote team --reconcile
```

## CI

The intended CI pattern is:

1. keep a ciphertext bundle such as `vault.age`
2. open it locally inside the job with `kimen bundle open`
3. run `doctor`, `envfile`, or `project run` against the job-local vault

The repo includes starter workflow templates in `.github/workflows/`:

- `kimen-pr-safety-template.yml`
- `kimen-deploy-template.yml`
- `kimen-sync-gate-template.yml`

You can scaffold them with:

```bash
kimen init ci-pr-safety
kimen init ci-deploy
kimen init ci-sync-gate
```

## Development

Enable the pre-commit hook:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
```

Run tests:

```bash
go test ./...
```

Run sync E2E smoke tests:

```bash
make sync-e2e
make sync-e2e-git
```
