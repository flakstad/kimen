# Kimen {{TAG}}

Release date: {{DATE}}

## Highlights

- local-first projection workflow improvements
- automation contract stability (`--json` + typed exits)
- CI/deploy readiness (maps/profiles, lint, doctor, bundle templates)

## Included in this release

### Core CLI

- `secret`: lifecycle + machine-friendly responses
- `vault`: JSON + typed exits
- `bundle`: JSON + typed exits
- `config`: JSON/typed failures on key paths
- `run` / `render` / `plan` / `envfile`: aligned machine behavior
- `remote` / `sync`: team sync orchestration + explicit operator subcommands

### Mapping and planning

- `map lint` with richer diagnostics
- `map lint --strict` to fail on warnings
- plan diff support (`--against-map`, `--against-profile`)
- `project plan` command

### Preflight and CI

- `doctor` command (`--strict`, optional bundle/identity preflight)
- CI workflow templates:
  - `.github/workflows/kimen-pr-safety-template.yml`
  - `.github/workflows/kimen-deploy-template.yml`
  - `.github/workflows/kimen-sync-gate-template.yml`

### Docs

- `docs/automation-contract.md`
- `docs/recommended-paths.md`
- `docs/ci-workflow-templates.md`

## Breaking changes

- None intended for this milestone.

## Known limitations

- Local-first runtime remains the core model; remote sync is optional.
- Team sync currently targets `fs` and `git` remotes only.
- No hosted coordination service in the shipped product today.

## Upgrade notes

- Verify automation against `docs/automation-contract.md`.
- Prefer strict checks in CI:
  - `kimen map lint --strict`
  - `kimen doctor --strict`
