# Kimen

Kimen is a local-first secret projection tool written in Clojure.

Kimen keeps secrets encrypted at rest in a local vault and only materializes them when you explicitly ask, for example as environment variables, files, or stdin for a command.

## Why Use Kimen

- No server required for runtime use.
- Explicit projection at command time.
- Script-friendly JSON output and typed exit codes.
- Team sync with encrypted bundle transport (`fs` and `git` remotes).

## Choose How You Run Kimen

### 1) Babashka CLI (default source workflow)

Prerequisites:

- Babashka (`bb`)
- Java + Clojure CLI (needed for JAR/native builds)

Install launcher on your path:

```bash
bb install
kimen version --json
```

The launcher (`bin/kimen` and installed `kimen`) runs Kimen through Babashka.

### 2) Native Binary

Prerequisite:

- GraalVM with `native-image`

Build and run:

```bash
bb build-native
./target/kimen version --json
```

### 3) JVM JAR

Build and run:

```bash
bb build-jar
java -jar target/kimen.jar version --json
```

### 4) Library API in Another Clojure Program

Use `kimen.api` for in-process embedding while keeping CLI behavior:

```clj
(require '[kimen.api :as kimen])

(kimen/run ["version" "--json"])
;; => {:exit-code 0 :stdout "...json...\n" :stderr nil}

(kimen/run! ["secret" "list" "--json"])
;; prints stdout/stderr, returns exit code
```

`kimen.api` exposes:

- `run`: returns `{:exit-code :stdout :stderr}`
- `run!`: emits stdout/stderr and returns exit code
- `main!`: CLI-style entrypoint returning exit code

## Quickstart

Initialize vault and set a secret:

```bash
# optional:
export KIMEN_VAULT="$HOME/.config/kimen/vault.db"

kimen vault init
echo -n 'shh' | kimen secret set api_key --stdin
```

Project into environment for one command:

```bash
kimen run --env API_KEY=api_key -- sh -lc 'echo "$API_KEY"'
```

Project into file(s):

```bash
OUTDIR="$(mktemp -d -t kimen-render.XXXXXX)"
kimen render --dir "$OUTDIR" --file config.txt=api_key
```

Write an envfile:

```bash
kimen envfile --env API_KEY=api_key --out .env.runtime
```

## Value Sources

Projection values can come from:

- `secret:<name>` or bare name (secret lookup)
- `const:<literal>`
- `exec:<command...>`

Example:

```bash
kimen run \
  --env API_KEY=secret:api_key \
  --env REGION=const:eu-north-1 \
  --env TOKEN=exec:./scripts/derive_token.sh \
  -- your-command
```

## Maps and Profiles

Use `.kmap` files to avoid repeating long flag lists:

```bash
mkdir -p .kimen/profiles
cat > .kimen/profiles/dev.kmap <<'EOF'
env API_KEY=api_key
file config.txt=api_key
EOF

kimen run --profile dev -- sh -lc 'echo "$API_KEY"'
```

Useful commands:

- `kimen map lint --profile <name> --strict --json`
- `kimen plan --profile <name> --json`
- `kimen project run|render|plan ...` (explicit aliases)

## Team Sync (Current)

Kimen supports local-first team sync using encrypted bundles and remotes.

Core commands:

- `kimen remote add|get|set|list|rm`
- `kimen sync`
- `kimen sync init|preflight|status|conflicts|changes|push|pull|resolve|reset-baseline|unlock|restore`

See:

- `docs/team-sync.md`
- `docs/team-sync-v1.md`
- `docs/team-sync-v2-plan.md`
- `docs/automation-contract.md`

## One-Time Migration from Go-Era Vault

If you still have a Go-era Kimen vault, migrate once into the Clojure vault:

```bash
bb migrate-go-vault -- \
  --source-bin /path/to/go-era-kimen \
  --source-vault /path/to/old-vault.db \
  --source-passphrase-cmd "printf old-passphrase" \
  --target-vault /path/to/new-vault.db \
  --target-passphrase-cmd "printf new-passphrase" \
  --init-target \
  --json
```

Guided helper:

```bash
scripts/migrate_current_vault.sh --yes
```

## Development and Validation

Run tests:

```bash
bb test
bb itest
bb e2e-sync-all
```

Validate all runtime modes (bb, JVM, jar, native, library):

```bash
bb smoke-modes
```

Build artifacts:

```bash
bb build-jar
bb build-native
```

Pre-commit hook (fast/no-op by default):

```bash
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
```

Opt-in checks on commit:

```bash
KIMEN_PRECOMMIT_TEST=1 git commit ...
KIMEN_PRECOMMIT_BUILD=1 git commit ...
```

## Release

Kimen uses CalVer tags: `vYYYY.M.PATCH` (example `v2026.3.0`).

Recommended flow:

```bash
bb test-all
bb e2e-sync-all
bb smoke-modes

git tag -a v2026.3.0 -m "Release v2026.3.0"
git push origin v2026.3.0
```

Tag push triggers `.github/workflows/release.yml`.

## Key Docs

- `docs/cli.md`: full CLI reference
- `docs/projections.md`: projection model and patterns
- `docs/maps.md`: `.kmap` and profile format
- `docs/automation-contract.md`: JSON envelopes and exit-code contract
- `docs/ci-github-actions.md`: CI integration patterns
- `docs/ci-workflow-templates.md`: workflow template usage
- `docs/recommended-paths.md`: recommended filesystem paths
- `docs/threat-model.md`: security model and boundaries
- `docs/stability.md`: compatibility and upgrade expectations
