# Kimen

Kimen is a local-first secrets tool.

It keeps secrets encrypted at rest in a local vault and projects them into runtime form only when you explicitly ask: env vars, files, envfiles, or a single scoped command execution.

The design goal is simple:

> keep secrets inert by default, and only make them usable for a specific command, file, or runtime context.

## What It Does

- Local encrypted vault
- Secret lifecycle commands: `set`, `list`, `get`, `rm`, `mv`
- Projection commands:
  - `kimen run`
  - `kimen render`
  - `kimen envfile`
  - `kimen plan`
  - `kimen project run|render|plan`
- Reusable projection specs via maps and profiles
- JSON output and typed exit codes for automation
- Optional Team Sync over `fs` and `git` remotes
- Bundle transport via `age`

## Install

Build and install:

```bash
make install
```

Or build without installing:

```bash
make build
./dist/kimen version
```

## Quickstart

Initialize a vault:

```bash
kimen vault init
```

Store a secret:

```bash
echo -n 'shh' | kimen secret set api_key --stdin
```

Use it for one command:

```bash
kimen run --env API_KEY=api_key -- printenv API_KEY
```

Render it to a file:

```bash
OUTDIR="$(mktemp -d)"
kimen render --dir "$OUTDIR" --file api-key.txt=api_key
```

Generate an envfile:

```bash
kimen envfile --env API_KEY=api_key --out .env.runtime
```

## Mental Model

- Secrets are stored encrypted at rest.
- A projection is a specific runtime form of a secret.
- Projections should be explicit, scoped, and short-lived.

In practice, most values come from the local vault by secret name, but mappings can also use:

- bare name: secret lookup
- `secret:<name>`: explicit secret lookup
- `const:<literal>`: inline literal
- `exec:<command...>`: derive value from a local command

## Maps And Profiles

Maps are line-oriented projection specs. Profiles are named map files.

Example `.kmap`:

```text
env API_KEY=api_key
file credentials.json=gcp_service_account_json
envpath GOOGLE_APPLICATION_CREDENTIALS=credentials.json
```

Run with a map:

```bash
kimen run --map .kimen/profiles/dev.kmap -- ./your-app
```

Run with a profile:

```bash
kimen run --profile dev -- ./your-app
```

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
- `kimen completion <bash|zsh|fish|powershell>`

Common conventions:

- vault precedence: `--vault` > `KIMEN_VAULT` > `config.vault.path` > default user config location
- passphrase sources: `KIMEN_PASSPHRASE`, `--passphrase-cmd`, `--passphrase-stdin`, configured unlock method, then TTY prompt
- machine-readable mode: `--json`

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

## Release

Releases use CalVer tags: `vYYYY.M.PATCH`.

Cut a release with:

```bash
make release-check
git tag -a v2026.2.1 -m "Release v2026.2.1"
git push origin v2026.2.1
```

The release workflow in [.github/workflows/release.yml](.github/workflows/release.yml) publishes GoReleaser artifacts for matching tags.
