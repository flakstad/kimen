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
```

### `kimen vault info`

What it does:

- Prints non-secret metadata about the vault (format version, KDF).

Use cases:

- Quick sanity checks (wrong file? unsupported format?).

Example:

```bash
kimen vault info
```

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
```

### `kimen secret list`

What it does:

- Lists stored secret names (optionally as JSON).

Use cases:

- Debugging what’s in a vault.
- Plumbing into other tools with `--json`.

Examples:

```bash
kimen secret list
kimen secret list --json
```

### `kimen secret get <name>` (discouraged)

What it does:

- Retrieves a secret and prints it to stdout **only** with `--unsafe-stdout`.

Use cases:

- Emergency escape hatch for manual debugging.

Why it’s discouraged:

- Printing secrets is easy to leak (shell history, logs, scrollback, copy/paste).
- Prefer `kimen run` / `kimen render` projections.

Examples:

```bash
kimen secret get api_key              # fails by default
kimen secret get api_key --unsafe-stdout | cat
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
```

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
```

Maps and profiles:

```bash
kimen render --dir "$OUTDIR" --map .kimen/profiles/linje-prod.kmap
kimen render --dir "$OUTDIR" --profile linje-prod
```

## `kimen plan`

What it does:

- Prints a “projection plan” showing what would materialize (secret names, env var names, file paths), **without values**.

Use cases:

- Reviewing changes (especially in CI).
- Debugging large profiles/maps safely.

Examples:

```bash
kimen plan --profile linje-prod --json -- clojure -M:dev
kimen plan --map .kimen/profiles/linje-prod.kmap
```

## `kimen envfile`

What it does:

- Writes a `KEY=VALUE` envfile from secret mappings, without printing secrets to stdout.

Use cases:

- systemd `EnvironmentFile=...` workflows.
- Deploy scripts that want a stable artifact.

Example:

```bash
kimen envfile --profile linje-prod --out /tmp/linje.env
```

### `kimen project …`

What it does:

- Provides the explicit grouped form of projection commands:
  - `kimen project run`
  - `kimen project render`

Use cases:

- Script clarity and future expansion.

In everyday usage, prefer the short verbs `kimen run` / `kimen render`.

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
```

### `kimen bundle recipient`

What it does:

- Prints the `age1...` recipient string corresponding to an identity file.

Use cases:

- Turn a stored identity into a recipient usable with `bundle seal`.

Example:

```bash
kimen bundle recipient --identity ci.agekey > ci.agepub
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
```
