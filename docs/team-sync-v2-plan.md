# Team Sync v2 Plan

This document defines the implementation plan for Team Sync v2.

It keeps Team Sync v1 guarantees intact:

- local-first runtime projections
- ciphertext-only remotes
- no remote dependency for runtime execution

## Product direction

v2 shifts sync UX from command-by-command choreography to orchestration-first behavior:

- default path: one safe command (`kimen sync`)
- opt-out path: explicit subcommands and flags for operators/scripts

Principle:

> Make the safe flow the easiest flow. Keep advanced controls explicit.

## Scope and non-goals

### In scope

- top-level sync orchestration with deterministic decision logic
- clearer machine-readable outcomes for orchestration mode
- reduced flag burden for common flows
- stronger conflict guidance for multi-writer teams

### Out of scope (v2)

- hosted coordination service
- redesign of canonical storage format
- plaintext secret/value exposure in remote metadata

## Milestones

## v2.1: Orchestration-first sync command

Goal:

- make `kimen sync` the default operator command

Status: implemented.

Behavior target:

- run readiness checks by default (doctor/status path)
- choose a safe action:
  - no-op (already aligned)
  - push (local-only changes)
  - pull (remote-only changes where safe)
  - block (risk/conflict/precondition)
- keep existing sync subcommands fully available

Proposed CLI:

```bash
kimen sync
kimen sync --dry-run
kimen sync --check
kimen sync --json
```

Opt-out flags:

- `--no-doctor`
- `--strict=false` (doctor strictness)
- existing explicit subcommands (`sync push/pull/status/conflicts/...`)

Implementation notes:

- conflict-safe default: avoid automatic pull when local unpushed changes may be overwritten
- preserve existing exit codes:
  - `31` conflict/manual reconciliation required
  - `32` non-conflict precondition failure

Acceptance gates:

- contract tests cover decisions: `noop`, `push`, `pull`, `blocked`
- fs/git e2e stays green
- `go test ./...` passes

## v2.2: Conflict intelligence

Goal:

- explain *what* changed, not just that a conflict exists

Status: implemented.

Additions:

- key-level change summaries in `sync changes --json`
- disjoint/overlap/conflict classification (`local_changed_keys`, `remote_changed_keys`, `overlapping_keys`, `conflict_keys`)

Acceptance gates:

- stable JSON schema in `docs/automation-contract.md`
- deterministic output in conflict scenarios

## v2.3: Guided reconcile

Goal:

- reduce manual conflict handling where it is safe

Status: implemented.

Additions:

- reconcile mode that auto-merges disjoint local/remote changes (`sync pull --reconcile`)
- overlapping key changes fail with explicit conflict reason (`overlapping_changes`)
- explicit key resolution command (`sync resolve --take local|remote --key ...`) to clear overlapping conflicts without force-resetting baselines

Acceptance gates:

- no silent data loss
- backup/restore invariants preserved
- tests cover disjoint vs overlapping edits and explicit resolution flow

## v2.4: Hardening and ergonomics polish

Goal:

- make orchestration robust for day-to-day team use

Status: implemented.

Additions:

- improved diagnostics for lock and remote state edge cases
- better defaults around remote selection
  - selection order: `KIMEN_REMOTE` -> unique sync-state remote -> `origin` -> only configured remote
- default orchestration path now auto-attempts `sync pull --reconcile` when local and remote both changed
- optional terse/human output tuning
- remote bootstrap command (`sync init`) with recipient derivation + next-step guidance

Acceptance gates:

- documented runbooks updated
- strict CI templates remain compatible

## v2.5: Documentation parity and API alignment

Goal:

- make docs the canonical reflection of shipped behavior

Status: implemented.

Scope:

- `README.md` (status, feature list, quickstart, sync workflows)
- `docs/cli.md` (all sync/remote/doctor/init surfaces and examples)
- `docs/automation-contract.md` (JSON/exit-code contract parity)
- `docs/team-sync-v1.md` + `docs/team-sync-v1-checklist.md` (operating runbooks and guarantees)
- `docs/team-sync.md` + `docs/team-sync-roadmap.md` (separate “implemented now” from directional roadmap)
- supporting docs that reference sync status/capabilities (`docs/when-to-use.md`, `docs/stability.md`, release notes/checklists)

Acceptance gates:

- no user-facing command examples that fail against current CLI surface
- no stale “proposed/future-only” wording for implemented Team Sync v1/v2 behavior
- README and docs agree on:
  - default orchestration flow (`kimen sync`)
  - conflict/reconcile model (`sync changes`, `sync pull --reconcile`, `sync resolve`)
  - strict preflight/gating patterns (`sync preflight --strict`, `doctor --strict`)
- docs clearly distinguish:
  - implemented v1/v2 sync behavior
  - directional post-v2 roadmap items

## Migration and compatibility

- existing `sync` subcommands remain source-compatible
- orchestration mode is additive and becomes the recommended default
- CI can continue to use strict explicit commands or move to `kimen sync --check --json`

## Work order (updated)

1. Complete v2.5 docs parity matrix (README + all sync/API docs). (done)
2. Update `README.md` as canonical operator entrypoint. (done)
3. Refresh `docs/cli.md` and `docs/automation-contract.md` to exact command/output behavior. (done)
4. Align team sync narrative docs (`team-sync-v1`, `team-sync`, `team-sync-roadmap`) with shipped v1/v2 state. (done)
5. Re-run command-example validation and e2e docs checks (`go test ./...`, `make go-sync-e2e-all`). (done)
