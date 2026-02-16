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
- CI/sync primitive: `kimen bundle seal/open` (ciphertext transport via `age`)
- Local remote sync: `kimen remote add/get/set/list/rm` + `kimen sync status/conflicts/push/pull/reset-baseline/unlock/restore` for `fs` and `git` remotes (push lock files apply to shared fs remotes)

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

Build and install from source:

```bash
make install
```

## Development

### Pre-commit hook (tests + build)

Hooks are stored in `.githooks/` and can be enabled via:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit
```

### Sync E2E Smoke Test

Run the end-to-end sync/conflict/recovery harness:

```bash
make sync-e2e
```

`make release-check` now includes this sync E2E harness.

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

Recommended release flow:

```bash
make release-check
git tag -a v2026.2.1 -m "Release v2026.2.1"
git push origin v2026.2.1
```

Or use the helper script:

```bash
scripts/release-calver.sh v2026.2.1 --push
```

Pushing a matching tag triggers `.github/workflows/release.yml`, which runs GoReleaser using `.goreleaser.yaml` and publishes release artifacts.

## Roadmap

Ideas and possible future projection types live in `docs/roadmap.md`.

## Docs

- `docs/cli.md`: full CLI/API reference with use-cases and how it works
- `docs/automation-contract.md`: canonical JSON output + exit-code contract for automation
- `docs/projections.md`: the projection model (why `run`/`render` exist)
- `docs/ci-github-actions.md`: CI pattern using bundles + projections
- `docs/ci-workflow-templates.md`: choose and adapt workflow templates in `.github/workflows/`
- `docs/recommended-paths.md`: recommended default paths for dev, CI, and systemd usage
- `docs/maps.md`: map files and profiles (`--map` / `--profile`)
- `docs/plan-1-2-3.md`: next implementation plan (maps/profiles, plan, envfile)
- `docs/when-to-use.md`: guidance on when Kimen fits (and when it doesn’t)
- `docs/alternatives.md`: adjacent tools and comparisons
- `docs/team-sync.md`: team collaboration models and future direction
- `docs/team-sync-roadmap.md`: team sync roadmap (directional)
- `docs/threat-model.md`: what Kimen does and does not protect against
- `docs/stability.md`: early-stage stability and upgrade expectations
- `docs/release-v0.1-checklist.md`: milestone release checklist
- `docs/release-notes-template.md`: draft template for GitHub release notes
- `docs/feedback-template.md`: structured early-adopter feedback template
