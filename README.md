# Kimen

Kimen is a local-first secrets tool.

Kimen stores secrets in an encrypted local vault and lets you project them into runtime only when you explicitly ask.

If you are currently juggling `.env` files, copied tokens, or long-lived shell exports, Kimen is meant to replace that with a safer, explicit workflow.

## Why Consider Kimen

- Local-first: no server required for core runtime use.
- Explicit secret projection: values are materialized only when requested.
- Good automation contract: typed exit codes and structured machine output.
- Team sync support (`fs` / `git`) without changing local runtime guarantees.

## Start Here

Kimen is primarily used as a native binary named `kimen`.

Check installation:

```bash
kimen version
```

## 5-Minute Tutorial

### 1) Initialize your vault

This creates the encrypted local vault file and asks you to choose a passphrase.
That passphrase protects the vault contents at rest.

```bash
kimen vault init
```

### 2) Store a secret

This command creates a secret entry named `demo_service_api_token` in your vault.
That name is just a label you choose; it is not a built-in command and not the secret value itself.

The command reads the secret value from stdin.
Type or paste the actual token, press Enter, then press Ctrl-D to finish stdin.

```bash
kimen secret set demo_service_api_token --stdin
```

### 3) Use it for one command only

This maps the vault secret name to an environment variable for one child process:

- `SERVICE_API_TOKEN` (left side) is the env var name seen by the child command.
- `demo_service_api_token` (right side) is the secret name in your vault.
- Everything after `--` is the real command that gets executed.

Why this is useful: the secret is available only for that one command, instead of being exported globally in your shell.

```bash
kimen run --env SERVICE_API_TOKEN=demo_service_api_token -- sh -lc 'echo "Token length: ${#SERVICE_API_TOKEN}"'
```

The secret exists only in that command process, not as a persistent shell export.

### 4) Render a secret to a file when a tool requires a file

Some tools only accept config files.
This writes `service-api-token.txt` from your vault secret with strict file permissions.

```bash
OUTDIR="$(mktemp -d -t kimen-render.XXXXXX)"
kimen render --dir "$OUTDIR" --file service-api-token.txt=demo_service_api_token
```

### 5) Generate an envfile for process supervisors

If your process manager expects an env file, generate one without printing secret values to terminal output.

```bash
kimen envfile --env SERVICE_API_TOKEN=demo_service_api_token --out .env.runtime
```

## Value Sources

Projection values can come from:

- `secret:<name>` (or just bare name)
- `const:<literal>`
- `exec:<command...>`

Example:

```bash
kimen run \
  --env SERVICE_API_TOKEN=secret:demo_service_api_token \
  --env REGION=const:eu-north-1 \
  --env TOKEN=exec:./scripts/derive_token.sh \
  -- your-command
```

## Maps and Profiles

Use `.kmap` files for repeatable intent:

```bash
mkdir -p .kimen/profiles
cat > .kimen/profiles/dev.kmap <<'EOF'
env SERVICE_API_TOKEN=demo_service_api_token
file service-api-token.txt=demo_service_api_token
EOF

kimen map lint --profile dev --strict
kimen plan --profile dev
kimen run --profile dev -- sh -lc 'echo "Token length: ${#SERVICE_API_TOKEN}"'
```

## Team Sync (Current)

Kimen supports encrypted bundle sync via `fs` and `git` remotes.

Main surfaces:

- `kimen remote add|get|set|list|rm`
- `kimen sync`
- `kimen sync init|preflight|status|conflicts|changes|push|pull|resolve|reset-baseline|unlock|restore`

For runbooks and contract details:

- `docs/team-sync.md`
- `docs/team-sync-v1.md`
- `docs/team-sync-v2-plan.md`
- `docs/automation-contract.md`

## Clojure Library Embedding

You can embed Kimen in another Clojure program through `kimen.api` (`run`, `run!`, `main!`) when you want in-process orchestration without spawning subprocesses.

The CLI remains the primary API surface; library embedding is for orchestration/tooling use-cases.

## Dependency Footprint

Runtime is intentionally minimal:

- Clojure + JDK
- One library dependency: `org.clojure/data.json`

There are no transitive runtime dependencies introduced by `clojure.data.json`.

Native-image helper dependencies are build-time only (`:native-image` alias), not part of normal runtime usage.

## Babashka Note

Babashka support exists as a convenience for Clojure users already in that ecosystem, but it is not the primary way this README presents Kimen usage.

## Machine Output (`--json` and `--edn`)

`--json` and `--edn` are both machine-output modes.
Use one or the other (they are mutually exclusive).

- `--json`: JSON objects
- `--edn`: idiomatic EDN maps with keyword keys

Examples:

```bash
kimen doctor --json
kimen doctor --edn
```

## More Docs

- `docs/cli.md`: full command reference
- `docs/projections.md`: projection model and security shape
- `docs/maps.md`: `.kmap` and profile format
- `docs/automation-contract.md`: machine contract and exit codes
- `docs/ci-github-actions.md`: CI integration patterns
- `docs/recommended-paths.md`: practical path conventions
- `docs/threat-model.md`: what Kimen protects and does not protect
