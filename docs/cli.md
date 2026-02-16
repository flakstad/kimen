# CLI / API (current)

Kimen is a local-first secret **projection** tool. The CLI is the primary API.

This document describes what each command does, how it works, and the typical use cases.

## Conventions

- Kimen defaults to a local vault path. You can override it with `--vault` or `KIMEN_VAULT`.
- Most commands that need secrets require an unlock passphrase:
  - `KIMEN_PASSPHRASE` (non-interactive)
  - `--passphrase-cmd "<command...>"` (read one line from command stdout)
  - `--passphrase-stdin` (read one line)
  - interactive terminal prompt (when available)
- Kimen tries hard to avoid printing secret values by default.
- Many commands accept `--map` / `--profile` to avoid repeating long mapping lists (see `docs/maps.md`).
- For machine integrations, see the canonical contract in `docs/automation-contract.md`.

## Environment variables

- `KIMEN_VAULT`: vault file path (overrides the default)
- `KIMEN_PASSPHRASE`: passphrase used to unlock the vault (non-interactive)
- `KIMEN_CONFIG`: config file path (overrides the default user config location)

When using file projections with `kimen run`, Kimen also sets:

- `KIMEN_FILES_DIR`: the directory where projected files were written for that run

## Passphrase storage and ergonomics

Today, Kimen does **not** ship an “unlock agent” or built-in OS keychain integration (see `docs/plan-1-2-3.md` for non-goals in the current milestone sequence). That means the CLI needs a passphrase source on each invocation.

Kimen’s lookup order is:

1) `KIMEN_PASSPHRASE` (if set)
2) explicit flags (`--passphrase-cmd`, `--passphrase-stdin`)
3) `kimen config unlock …` (default method)
4) interactive prompt (TTY only)

Good practical options:

- **Interactive typing (default):** safest and simplest; Kimen prompts when stdin is a TTY.
- **One terminal session (local dev):** set `KIMEN_PASSPHRASE` in your current shell session (not in dotfiles), then `unset KIMEN_PASSPHRASE` when done.
- **OS keychain / password manager (recommended):** store the passphrase in your OS credential store, and configure a default unlock command once via `kimen config unlock set exec -- ...`.
- **CI:** set `KIMEN_PASSPHRASE` as a CI secret and run non-interactively.

Notes:

- The config file stores **how to obtain** the passphrase (method + optional command), not the passphrase itself.
- `unlock.method=stdin` is generally not recommended because it can conflict with commands that also read values from stdin (e.g. `kimen secret set --stdin`).
- Why not “store the passphrase in a local auth file”, like gcloud?
  - gcloud’s local credentials are typically used to obtain **revocable, short-lived** tokens from a server with centralized IAM/policy controls.
  - Kimen is designed to work **offline**; if you store the vault-unlock secret in a local file, the vault’s security largely becomes “can an attacker read/copy that file?”.
  - Prefer OS-backed secure storage (Keychain/Secret Service/1Password/Bitwarden/etc) via `unlock.method=exec`.

Examples:

```bash
# Configure macOS Keychain (store the item via Keychain Access; then):
kimen config unlock set exec -- security find-generic-password -w -s kimen/vault

# Configure 1Password CLI:
kimen config unlock set exec -- op read op://Personal/kimen-vault/passphrase

# Configure Bitwarden CLI (requires you to be logged in/unlocked; see bw docs):
kimen config unlock set exec -- bw get password kimen-vault

# Configure Google Secret Manager (requires gcloud auth; adds a network dependency):
kimen config unlock set exec -- gcloud secrets versions access latest --secret kimen-vault-passphrase
```

## `kimen config …`

Kimen config controls local CLI behavior (primarily passphrase unlock defaults). It does not store secret values.

### `kimen config path`

What it does:

- Prints the config file path.
- With `--json`, emits a structured success object.

Examples:

```bash
kimen config path
kimen config path --json
```

### `kimen config show`

What it does:

- Shows the full current config JSON.

Examples:

```bash
kimen config show
kimen config show --pretty=false
```

### `kimen config unlock set/show/clear`

What it does:

- `set`: configure default unlock method (`prompt`, `env`, `stdin`, `exec`).
- `show`: show current unlock method.
- `clear`: remove unlock config and revert to `prompt`.

Examples:

```bash
kimen config unlock set env
kimen config unlock set exec -- security find-generic-password -w -s kimen/vault
kimen config unlock show
kimen config unlock show --json
kimen config unlock clear --json
```

Automation notes:

- `config path --json`, `config unlock set/show/clear --json` emit JSON success payloads.
- `config show` is already JSON by default and emits JSON error envelopes on failure.
- config failures use exit code `26`.

## `kimen vault …`

### `kimen vault init`

What it does:

- Creates a new local vault file, encrypted at rest.

Use cases:

- First-time setup on a machine.
- Creating a separate vault for a repo or environment (by pointing `KIMEN_VAULT` at a different path).

How it works (high level):

- Derives a key-encryption-key (KEK) from your passphrase (Argon2id).
- Generates a random data-encryption-key (DEK) for encrypting secret records.
- Stores the DEK wrapped under the KEK, plus format/KDF metadata.

Examples:

```bash
export KIMEN_VAULT="$HOME/.config/kimen/vault.db"
kimen vault init  # prompts for passphrase
kimen vault init --json
```

### `kimen vault info`

What it does:

- Prints non-secret metadata about the vault (format version, KDF).

Use cases:

- Quick sanity checks (wrong file? unsupported format?).

Example:

```bash
kimen vault info
kimen vault info --json
```

Automation notes:

- `vault init --json` and `vault info --json` emit JSON success payloads.
- On failure with `--json`, vault commands emit JSON error envelopes on stderr.
- vault failures use:
  - `14`: vault not found
  - `15`: wrong passphrase / corrupted vault
  - `24`: other vault command failures

## `kimen secret …`

### `kimen secret set <name>`

What it does:

- Stores or updates a secret value under a name.

Use cases:

- Put API keys, tokens, passwords, config snippets, etc. into the vault.

Input modes:

- `--stdin`: read raw bytes from stdin (recommended for scripts)
- interactive prompt (TTY)

Examples:

```bash
echo -n 'shh123' | kimen secret set api_key --stdin
kimen secret set api_key --stdin --json
```

### `kimen secret list`

What it does:

- Lists stored secret names.
- With `--json`, returns a structured object with `names` and `count`.

Use cases:

- Debugging what’s in a vault.
- Plumbing into other tools with `--json`.

Examples:

```bash
kimen secret list
kimen secret list --json
```

### `kimen secret rm <name>`

What it does:

- Removes a secret by name.

Use cases:

- Removing obsolete credentials.
- Cleaning up temporary/bootstrap secrets after migration.

Examples:

```bash
kimen secret rm api_key
kimen secret rm api_key --json
```

### `kimen secret mv <old-name> <new-name>`

What it does:

- Renames a secret while preserving its value and metadata.

Use cases:

- Namespace cleanup (for example, `api_key` -> `linje.prod.api_key`).
- Aligning secret names with profile/map conventions.

Examples:

```bash
kimen secret mv api_key linje.prod.api_key
kimen secret mv api_key linje.prod.api_key --json
```

### `kimen secret get <name>` (discouraged)

What it does:

- Retrieves a secret and prints it to stdout **only** with `--unsafe-stdout`.
- With `--json`, emits a structured response with `value_b64` (base64-encoded secret value).

Use cases:

- Emergency escape hatch for manual debugging.

Why it’s discouraged:

- Printing secrets is easy to leak (shell history, logs, scrollback, copy/paste).
- Prefer `kimen run` / `kimen render` projections.

Examples:

```bash
kimen secret get api_key              # fails by default
kimen secret get api_key --unsafe-stdout | cat
kimen secret get api_key --unsafe-stdout --json
```

### Secret command exit codes

Kimen now uses typed exit codes for common `secret` failures:

- `12`: secret not found
- `13`: secret already exists (rename destination conflict)
- `14`: vault not found
- `15`: wrong passphrase / corrupted vault
- `1`: other errors

These codes apply to `secret set/list/get/rm/mv` and are useful for scripts/CI.

## `kimen map …`

### `kimen map lint`

What it does:

- Lints a map file (or profile) without materializing any secrets.

Checks include:

- empty map files (no effective mappings) as errors
- duplicate/conflicting `env` variable mappings
- duplicate/conflicting `file` relative paths
- duplicate/conflicting `envpath` variable mappings
- `envpath` vars that shadow `env` vars with the same name as warnings
- `envpath` entries that refer to missing `file` projections
- projected file path/directory conflicts (for example `file creds=...` and `file creds/token=...`)
- run-only/mode-specific warnings (for example `stdin` and `envpath` envfile behavior)
- warnings when a map has only file mappings (so `kimen envfile` would fail)
- warnings for likely shell-sensitive `exec:` specs that may need wrapper scripts
- warnings when profile resolution has shadowed candidates in lower-precedence locations

Examples:

```bash
kimen map lint --map .kimen/profiles/linje-prod.kmap
kimen map lint --profile linje-prod --json
kimen map lint --profile linje-prod --strict
```

Exit behavior:

- exits `0` when lint has no errors (warnings are allowed)
- with `--strict`, warnings are treated as failures
- exits `20` when lint fails

## `kimen doctor`

What it does:

- Runs local preflight checks for common automation/deploy prerequisites.
- Emits human output by default, or JSON with `--json`.

Checks include:

- config path resolution and config JSON validity
- passphrase source readiness for non-interactive usage
- vault path/file/metadata/permissions
- optional map/profile parse + lint checks (`--map` or `--profile`)
- optional bundle decryptability preflight (`--bundle-in` + `--identity`)

Examples:

```bash
kimen doctor
kimen doctor --profile linje-prod --json
kimen doctor --profile linje-prod --strict --json
kimen doctor --allow-missing-vault
kimen doctor --bundle-in vault.age --identity ci.agekey --json
```

Exit behavior:

- exits `0` when there are no doctor errors
- with `--strict`, warnings are treated as failures
- exits `27` when doctor fails

## `kimen version`

What it does:

- Prints build information.
- Release builds carry CalVer tag + commit/date metadata.

Examples:

```bash
kimen version
kimen version --json
```

## Projections: `kimen run`, `kimen render`, `kimen project …`

Kimen’s primary workflow is to *project* secrets into a runtime form on demand.

See `docs/projections.md` for the conceptual model.

### `kimen run -- …`

What it does:

- Runs a child process with secrets projected into:
  - environment variables (`--env VAR=<value>`)
  - files (`--file relpath=<value>`)
  - stdin (`--stdin <value>`)

Use cases:

- Local dev: run a tool with secrets without permanently exporting env vars.
- CI: run build/deploy steps with secrets scoped to that one step.
- systemd wrappers: use `kimen run -- <service>` for a tightly scoped execution.

How it works:

- Resolves required values (vault secrets and/or `exec:` sources).
- Applies env overrides for the child process.
- If `--file` is used, writes the projected files under a directory and sets `KIMEN_FILES_DIR`.
  - If `--files-dir` is not provided, a temp directory is created and removed after the command exits.

Examples:

```bash
# Env projection:
kimen run --env API_KEY=api_key -- curl -H "Authorization: Bearer $API_KEY" https://example.com

# File projection (temp dir by default):
kimen run --file cfg.txt=api_key -- sh -lc 'cat "$KIMEN_FILES_DIR/cfg.txt"'

# Stdin projection:
kimen run --stdin api_key -- sh -lc 'cat -'
```

Maps and profiles:

```bash
kimen run --map .kimen/profiles/linje-prod.kmap -- clojure -M:dev
kimen run --profile linje-prod -- clojure -M:dev
```

Dry-run planning:

```bash
kimen run --profile linje-prod --dry-run -- clojure -M:dev
kimen run --profile linje-prod --dry-run --json -- clojure -M:dev
```

Automation notes:

- setup/projection failures use typed exit codes (`12`, `14`, `15`, `23`)
- when the child command starts and exits non-zero, Kimen forwards the child exit code
- `kimen run --json` emits structured error envelopes on stderr for setup/projection failures

### Value sources (`secret name` vs `exec:...`)

Wherever Kimen accepts a value on the right-hand side (e.g. `--env VAR=...`, `--file path=...`, `--stdin ...`), the default is a vault secret name.

Kimen also supports deriving values at projection time:

- `exec:<command...>`: run a local command and use its stdout (with one trailing newline stripped)
  - Note: arguments are split on whitespace (no shell parsing/quoting). Use a wrapper script for complex cases.

### `kimen render`

What it does:

- Writes projected secret files into an output directory.

Use cases:

- Generate config files for tooling that can’t easily be wrapped with `kimen run`.
- systemd-style workflows where you manage the directory lifecycle yourself.

How it works:

- Creates the output dir if missing.
- Writes files with mode `0600` and directories with mode `0700`.
- Does **not** clean up automatically.

Example:

```bash
OUTDIR="$(mktemp -d -t kimen-render.XXXXXX)"
kimen render --dir "$OUTDIR" --file cfg.txt=api_key
kimen render --dir "$OUTDIR" --file cfg.txt=api_key --json
```

Systemd-friendly runtime mode:

```bash
kimen render \
  --systemd-service linje-api \
  --runtime-dir /run \
  --file cfg.txt=api_key \
  --print-systemd-hints
```

This mode renders to `<runtime-dir>/kimen/<service>` with predictable permissions (`0700` dir, `0600` files).

Automation notes:

- render setup/projection failures use typed exit codes (`12`, `14`, `15`, `23`)
- `kimen render --json` emits a JSON success object on stdout
- on failure, `kimen render --json` emits a structured JSON error envelope on stderr

Maps and profiles:

```bash
kimen render --dir "$OUTDIR" --map .kimen/profiles/linje-prod.kmap
kimen render --dir "$OUTDIR" --profile linje-prod
```

## `kimen plan`

What it does:

- Prints a “projection plan” showing what would materialize (secret names, env var names, file paths), **without values**.
- Can optionally compute a projection diff with `--against-map` or `--against-profile`.

Use cases:

- Reviewing changes (especially in CI).
- Debugging large profiles/maps safely.
- Comparing intended projection changes before rollout.

Examples:

```bash
kimen plan --profile linje-prod --json -- clojure -M:dev
kimen plan --map .kimen/profiles/linje-prod.kmap
kimen plan --map .kimen/profiles/new.kmap --against-map .kimen/profiles/current.kmap --json
kimen plan --profile new --against-profile current
```

When diff flags are used, output includes:

- added/removed/changed env mappings (by env var target)
- added/removed/changed file mappings (by relative file path)
- added/removed/changed `envpath` mappings (by env var target)
- stdin source changes (when applicable)

Automation notes:

- `kimen plan --json` now emits a JSON error envelope on stderr when planning fails.
- plan failures use exit code `21`.

## `kimen envfile`

What it does:

- Writes a `KEY=VALUE` envfile from secret mappings, without printing secrets to stdout.

Use cases:

- systemd `EnvironmentFile=...` workflows.
- Deploy scripts that want a stable artifact.

Example:

```bash
kimen envfile --profile linje-prod --out /tmp/linje.env
kimen envfile --profile linje-prod --out /tmp/linje.env --json
```

Automation notes:

- `kimen envfile --json` emits a JSON success object on stdout.
- On failure, `--json` emits a JSON error envelope on stderr.
- Generic envfile failures use exit code `22` (with secret/vault failures reusing typed secret codes).

### `kimen project …`

What it does:

- Provides the explicit grouped form of projection commands:
  - `kimen project run`
  - `kimen project render`
  - `kimen project plan`

Use cases:

- Script clarity and future expansion.

In everyday usage, prefer the short verbs `kimen run` / `kimen render` / `kimen plan`.

## Bundles: `kimen bundle …` (sync/CI primitive)

Bundles are Kimen’s “no-trust transport” story: move vaults as ciphertext through untrusted storage.

See `docs/ci-github-actions.md` for a concrete CI pattern.

### `kimen bundle keygen`

What it does:

- Generates an `age` X25519 identity file (private key) for bundle encryption/decryption.

Use cases:

- Create a CI identity (private key stored in GitHub Actions Secrets).
- Create a per-device identity for local sync workflows.

Example:

```bash
kimen bundle keygen --out ci.agekey --print-recipient > ci.agepub
kimen bundle keygen --out ci.agekey --json
```

### `kimen bundle recipient`

What it does:

- Prints the `age1...` recipient string corresponding to an identity file.

Use cases:

- Turn a stored identity into a recipient usable with `bundle seal`.

Example:

```bash
kimen bundle recipient --identity ci.agekey > ci.agepub
kimen bundle recipient --identity ci.agekey --json
```

### `kimen bundle seal`

What it does:

- Encrypts the vault file to one or more recipients, producing a ciphertext bundle.

Use cases:

- Store `vault.age` in a repo or blob store for CI.
- Sync `vault.age` across machines using Dropbox/iCloud/Syncthing/etc.

Example:

```bash
kimen bundle seal --vault "$KIMEN_VAULT" --out vault.age --recipient "$(cat ci.agepub)"
kimen bundle seal --vault "$KIMEN_VAULT" --out vault.age --recipient "$(cat ci.agepub)" --json
```

### `kimen bundle open`

What it does:

- Decrypts a bundle into a local vault file using an identity.

Use cases:

- CI job: open a bundle into a temp location, then run commands with projections.
- New machine bootstrap: open the vault locally, then use it normally.

Example:

```bash
kimen bundle open --in vault.age --out-vault "$KIMEN_VAULT" --identity ci.agekey --overwrite
kimen bundle open --in vault.age --out-vault "$KIMEN_VAULT" --identity ci.agekey --overwrite --json
```

Automation notes:

- `bundle keygen/recipient/seal/open --json` emit JSON success payloads.
- On failure with `--json`, bundle commands emit JSON error envelopes on stderr.
- bundle failures use exit code `25`.

## Remotes: `kimen remote …`

Remotes define where encrypted vault bundles are synchronized.

Current support:

- `type=fs` (filesystem path; either a directory containing `vault.age` or a direct `.age` file path)

### `kimen remote add <name>`

What it does:

- Adds a named remote to local config.
- Stores remote transport information plus optional sync credentials.

Examples:

```bash
# Directory remote (bundle path becomes /srv/kimen/team-vault/vault.age)
kimen remote add team \
  --type fs \
  --path /srv/kimen/team-vault \
  --recipient age1... \
  --identity ~/.config/kimen/team.agekey

# Direct bundle file path:
kimen remote add team --type fs --path /srv/kimen/team-vault.age --recipient age1... --identity ~/.config/kimen/team.agekey
```

### `kimen remote get <name>`

What it does:

- Shows one configured remote in human or JSON form.

Examples:

```bash
kimen remote get team
kimen remote get team --json
```

### `kimen remote set <name>`

What it does:

- Updates an existing remote without removing/re-adding it.
- Accepts partial updates for `--type`, `--path`, `--recipient`, `--identity`.
- If `--type` or `--path` changes, sync baseline for that remote is cleared to avoid stale revision assumptions.

Examples:

```bash
kimen remote set team --path /srv/kimen/new-team-vault
kimen remote set team --recipient age1new...
kimen remote set team --path /srv/kimen/new-team-vault --json
```

### `kimen remote list`

What it does:

- Lists configured remotes.

Examples:

```bash
kimen remote list
kimen remote list --json
```

### `kimen remote rm <name>`

What it does:

- Removes a remote definition.
- Also removes sync baseline state for that remote (`last_seen_rev`).

Example:

```bash
kimen remote rm team
kimen remote rm team --json
```

Automation notes:

- `remote add/get/set/list/rm --json` emit JSON success payloads.
- remote failures use exit code `30`.

## Sync: `kimen sync …`

Sync uses a local baseline (`last_seen_rev`) to detect remote changes before push.

### `kimen sync status`

What it does:

- Compares local baseline state to the remote bundle revision.
- Reports whether pushing is safe or pulling is required.

Key fields (`--json`):

- `has_remote`: remote bundle exists
- `has_lock`: remote push lock file exists
- `lock_path`, `lock_age`, `lock_pid`, `lock_host`, `lock_user`: lock metadata when present
- `last_seen_rev`: last remote revision observed by local machine
- `in_sync`: local baseline matches current remote revision
- `can_push`: push is allowed without conflict
- `needs_pull`: remote changed or baseline is missing

Examples:

```bash
kimen sync status --remote team
kimen sync status --remote team --json
```

### `kimen sync push`

What it does:

- Encrypts the local vault to the remote bundle path.
- Updates `last_seen_rev` on success.
- Uses a lock file (`<bundle>.lock`) to avoid concurrent `sync push` writes on the same remote path.

Requirements:

- remote must have `recipient` configured
- local vault file must exist
- remote baseline check must pass
- push lock must be available (or waited for via `--lock-wait`)

Examples:

```bash
kimen sync push --remote team
kimen sync push --remote team --lock-wait 15s
kimen sync push --remote team --break-stale-lock-after 30m
kimen sync push --remote team --json
```

### `kimen sync conflicts`

What it does:

- Explains whether push is blocked by a baseline conflict and why.
- Returns conflict details (`reason`, expected/actual rev) without mutating state.
- Also reports lock state (`has_lock`, lock metadata), useful when contention and conflict happen together.

Examples:

```bash
kimen sync conflicts --remote team
kimen sync conflicts --remote team --json
```

### `kimen sync pull`

What it does:

- Decrypts the remote bundle into the local vault file (overwrite).
- Creates a timestamped local vault backup before overwrite (default).
- Updates `last_seen_rev` to the pulled remote revision.

Requirements:

- remote must have `identity` configured
- remote bundle must exist

Examples:

```bash
kimen sync pull --remote team
kimen sync pull --remote team --no-backup
kimen sync pull --remote team --json
```

### `kimen sync reset-baseline` (dangerous)

What it does:

- Manually overrides baseline state for a remote when you intentionally want to bypass normal conflict flow.
- Requires explicit `--yes`.

Modes (choose exactly one):

- `--to-remote`: set baseline to current remote revision
- `--clear`: remove baseline
- `--rev <sha>`: set baseline to an explicit revision

Examples:

```bash
kimen sync reset-baseline --remote team --to-remote --yes
kimen sync reset-baseline --remote team --clear --yes
kimen sync reset-baseline --remote team --rev abc123... --yes --json
```

### `kimen sync restore`

What it does:

- Restores local vault from a backup file.
- Backs up the current local vault first (default), unless `--no-backup` is set.

Examples:

```bash
kimen sync restore --backup ~/.config/kimen/vault.db.bak.123456789
kimen sync restore --backup ~/.config/kimen/vault.db.bak.123456789 --no-backup --json
```

### `kimen sync unlock` (emergency)

What it does:

- Removes a remote push lock file (`<bundle>.lock`) when a lock is orphaned.
- Requires `--yes` before removing an existing lock.
- Optional age guard with `--if-older-than`.

Examples:

```bash
kimen sync unlock --remote team --yes
kimen sync unlock --remote team --if-older-than 10m --yes --json
```

### Sync conflicts and what to do

When `sync push` returns conflict exit code `31`, it means your local baseline does not match the remote state.

Common conflict cases:

- remote changed since your last sync
- remote bundle was deleted since your last sync
- remote already has data, but your local machine has no baseline yet

Concrete recovery flow:

```bash
# 1) inspect state
kimen sync status --remote team --json

# 2) pull latest remote snapshot
kimen sync pull --remote team

# 3) re-apply local changes (if needed)
kimen secret set api_key --stdin

# 4) push again
kimen sync push --remote team
```

Automation notes:

- `sync` conflicts use exit code `31`.
- other sync failures use exit code `32`.
