# Kimen

Kimen is a local-first tool for bringing secrets into usable runtime form. It stores secrets securely at rest and produces short-lived, context-specific projections—such as environment variables, rendered configuration files, or scoped execution contexts—only when they are intentionally requested.

Kimen treats secrets as *latent capabilities*, not static values: secrets remain inert by default, and only become “real” in a specific form when intent is expressed.

## Why Kimen

Kimen solves one problem especially well:

> “I want secrets encrypted at rest locally, but I need them to show up only at the moment I run something, as env vars or files, and then disappear.”

That’s what projections are for: `kimen run` scopes secrets to a single process, and file projections can live in a temp directory that is removed immediately after the command exits.

## Status

Kimen is early-stage and evolving. The CLI and on-disk formats may change.
Releases use CalVer tags: `vYYYY.M.PATCH` (for example, `v2026.2.1`).

## Project Surfaces

Kimen now has two active surfaces:

- **CLI/runtime (this repo)**: local-first vault, projections, Team Sync, and automation contract.
- **Website v1 (separate `kimen-site` repo)**: landing and product positioning for onboarding.

Hosted signup/app-shell flows remain optional and directional; they are not required for Kimen runtime behavior.

## Recent Improvements

Recent shipped milestones include:

- Team Sync v2 orchestration defaults (`kimen sync`) and guided conflict/reconcile flows.
- Script-facing contract hardening (`exit_code` on success envelopes + machine-readable `reason` codes across command families).
- Shared reason-code catalog + anti-drift tests to keep JSON error semantics stable.
- CI workflow template fixes and scaffold parity (`ci-deploy`, `ci-sync-gate`, `ci-pr-safety`).
- CalVer release flow with tag-driven GitHub release automation.

## Team Sync (current state)

Team Sync v1 + v2 behavior is implemented for `fs` and `git` remotes, including:

- orchestration-first default `kimen sync` (with `--check` / `--dry-run` / `--json`)
- `sync init/preflight/changes/resolve/status/conflicts/push/pull/reset-baseline/unlock/restore`
- disjoint-merge pulls via `sync pull --reconcile`
- orchestration auto-reconcile for disjoint local+remote edits (`kimen sync` selects reconcile path)
- explicit overlap conflict handling via `sync resolve --take local|remote`
- strict CI gating via `sync preflight --strict`
- no-mutation preflight via `sync pull --dry-run` and `sync push --dry-run`
- remote readiness + sync-state checks in `kimen doctor`
- bootstrap/setup ergonomics via `sync init` and automatic recipient derivation in `remote add/set` (with opt-out flag)

See:

- `docs/team-sync-v1.md`
- `docs/team-sync-v1-checklist.md`
- `docs/team-sync-v2-plan.md`
- `docs/automation-contract.md`

## Mental model

- **Secrets** are encrypted, inert source material.
- **Intent** is an explicit request for a secret *in a particular form*, for a particular use.
- **Projections** are the realized output (env vars, files, exec contexts, etc.) with an explicit lifetime.

In practice, most projections read bytes from the local vault by secret name. Kimen can also support producing projected bytes at projection time (e.g. from local commands) while keeping the same projection-first workflow.

## Features (current)

- Local encrypted vault (`vault.db`)
- Secret lifecycle commands (`secret set/list/rm/mv`, with `secret get` as an escape hatch)
- Script-friendly JSON + typed exit codes across `secret`, `vault`, `config`, `bundle`, `plan`, `envfile`, and projection commands
- Projections:
  - `kimen run` (scoped env/files for a single command)
  - `kimen render` (write secret files with strict perms)
  - `kimen project run/render/plan` (explicit grouped projection commands)
  - `kimen render --systemd-service ...` (predictable runtime path mode for service wiring)
  - `kimen envfile` (write `KEY=VALUE` envfiles without printing secrets)
- Repeatable intent: `--map` / `--profile`
- Plan diffing: `kimen plan --against-map/--against-profile`
- Map/profile linting: `kimen map lint` (with optional `--strict`)
- Preflight checks: `kimen doctor` (human/JSON, strict mode)
- Build metadata: `kimen version` (`--json` supported)
- Safe planning: `kimen plan` (no secret values)
- CI scaffolding: `kimen init ci-pr-safety|ci-deploy|ci-sync-gate` (generate workflow starters)
- CI/sync primitive: `kimen bundle seal/open` (ciphertext transport via `age`)
- Local remote sync: `kimen remote add/get/set/list/rm` + orchestration/default sync (`kimen sync`) + explicit sync subcommands (`init/preflight/changes/resolve/status/conflicts/push/pull/reset-baseline/unlock/restore`) for `fs` and `git` remotes (push lock files apply to shared fs remotes)

## Projections

Kimen’s core abstraction is the **projection**: secrets are inert at rest and only become usable when you explicitly project them into a runtime shape.

Common entrypoints:

- `kimen run` (preferred): run a command with projected env/files
- `kimen render`: render projected files into a directory
- `kimen project ...`: explicit grouped projection commands

See `docs/projections.md`.

## Local-first, but what about teams and business models?

Kimen is designed to be complete and useful with **no server**. If there’s a business play, it’s in optional coordination (sync, membership, auditing) rather than centralizing runtime or turning Kimen into a hosted vault.

See `docs/business.md`.

## Quickstart (current CLI)

Prerequisites:

- Babashka (`bb`) for the default launcher and test loop
- Java + Clojure CLI for JAR/native builds

Build and install the Clojure launcher from source:

```bash
bb install
```

Build a JAR artifact:

```bash
bb build-jar
```

Build a native binary (requires GraalVM + `native-image`):

```bash
bb build-native
```

Legacy Go build/test targets remain available as `go-*` targets during transition (for example `make go-test`).

### One-Time Go Vault Migration

Before removing the Go implementation from your workflow, migrate secrets from a Go-era vault into the Clojure vault:

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

Dry-run mode is supported via `--dry-run` to validate source reads without writing target secrets.

For an operator-friendly guided flow (backup + dry-run + migrate + verify), use:

```bash
scripts/migrate_current_vault.sh --yes
```

Run `scripts/migrate_current_vault.sh --help` for non-interactive/passphrase file options.

## Development

### Pre-commit hook

Hooks are stored in `.githooks/` and can be enabled via:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
```

By default the hook is fast/no-op.
Opt in when needed:

```bash
KIMEN_PRECOMMIT_TEST=1 git commit ...
KIMEN_PRECOMMIT_BUILD=1 git commit ...
```

### Sync E2E Smoke Test

Run the end-to-end sync/conflict/recovery harness:

```bash
bb e2e-sync
bb e2e-sync-git
```

`bb e2e-sync-all` runs both harnesses.
Legacy Go validation remains available during transition via `make go-release-check`.

Set a vault location (optional) and initialize:

```bash
export KIMEN_VAULT="$HOME/.config/kimen/vault.db"
kimen vault init
```

Add a secret:

```bash
echo -n 'shh' | kimen secret set api_key --stdin
```

Run a command with projected secrets (env + files):

```bash
kimen run --env API_KEY=api_key --file config.txt=api_key -- printenv API_KEY
```

Value sources:

- bare value (default): secret name lookup
- `secret:<name>`: explicit secret lookup
- `const:<literal>`: inline literal value
- `exec:<command...>`: derive value from command stdout

Create a map/profile (optional, recommended for real projects):

```bash
mkdir -p .kimen/profiles
cat > .kimen/profiles/dev.kmap <<'EOF'
env API_KEY=api_key
EOF
kimen run --profile dev -- printenv API_KEY
```

Export/import the vault as an age-encrypted bundle (useful for CI and “no-trust” sync transports):

```bash
kimen bundle keygen --out ci.agekey --print-recipient > ci.agepub
kimen bundle seal --out vault.age --recipient "$(cat ci.agepub)"
kimen bundle open --in vault.age --out-vault "$KIMEN_VAULT" --identity path/to/age.key --overwrite
```

See also `docs/ci-github-actions.md`.

## Release (CalVer)

Kimen releases use CalVer tags: `vYYYY.M.PATCH` (example: `v2026.2.1`).
GitHub release automation triggers only for tags matching: `v[0-9][0-9][0-9][0-9].[0-9]*.[0-9]*`.

Recommended release flow:

```bash
# 1) Validate branch state
make release-check

# 2) Create annotated CalVer tag
git tag -a v2026.2.1 -m "Release v2026.2.1"

# 3) Push tag to cut release
git push origin v2026.2.1
```

Or use the helper script:

```bash
scripts/release-calver.sh v2026.2.1 --push
```

Verify:

```bash
git tag -l "v2026.*"
gh run list --workflow release.yml --limit 5
```

Pushing a matching tag triggers `.github/workflows/release.yml`, which builds and publishes:

- `dist/kimen` (Babashka launcher script)
- `dist/kimen.jar` (JVM artifact)
- `dist/SHA256SUMS`

## Roadmap

Ideas and possible future projection types live in `docs/roadmap.md`.
Web/product track status and next steps are also tracked there.

## Docs

- `docs/cli.md`: full CLI/API reference with use-cases and how it works
- `docs/automation-contract.md`: canonical JSON output + exit-code contract for automation
- `docs/projections.md`: the projection model (why `run`/`render` exist)
- `docs/ci-github-actions.md`: CI pattern using bundles + projections
- `docs/ci-workflow-templates.md`: choose and adapt workflow templates in `.github/workflows/`
- `docs/recommended-paths.md`: recommended default paths for dev, CI, and systemd usage
- `docs/maps.md`: map files and profiles (`--map` / `--profile`)
- `docs/plan-1-2-3.md`: historical milestone plan (maps/profiles, plan, envfile)
- `docs/when-to-use.md`: guidance on when Kimen fits (and when it doesn’t)
- `docs/alternatives.md`: adjacent tools and comparisons
- `docs/team-sync.md`: Team Sync current model, runbooks, and boundaries
- `docs/team-sync-roadmap.md`: delivered sync milestones + directional next phases
- `docs/team-sync-v1.md`: Team Sync v1 guarantees, operating models, and runbooks
- `docs/team-sync-v2-plan.md`: Team Sync v2 implementation plan and status
- `docs/team-sync-v1-checklist.md`: Team Sync v1 exit checklist
- `docs/threat-model.md`: what Kimen does and does not protect against
- `docs/stability.md`: early-stage stability and upgrade expectations
- `docs/release-v0.1-checklist.md`: milestone release checklist
- `docs/release-notes-template.md`: draft template for GitHub release notes
- `docs/feedback-template.md`: structured early-adopter feedback template
