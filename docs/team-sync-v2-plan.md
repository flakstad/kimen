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

Status: implemented initial version.

Additions:

- key-level change summaries in `sync changes --json`
- disjoint/overlap/conflict classification (`local_changed_keys`, `remote_changed_keys`, `overlapping_keys`, `conflict_keys`)

Acceptance gates:

- stable JSON schema in `docs/automation-contract.md`
- deterministic output in conflict scenarios

## v2.3: Guided reconcile

Goal:

- reduce manual conflict handling where it is safe

Status: implemented initial version.

Additions:

- reconcile mode that auto-merges disjoint local/remote changes (`sync pull --reconcile`)
- overlapping key changes fail with explicit conflict reason (`overlapping_changes`)

Acceptance gates:

- no silent data loss
- backup/restore invariants preserved
- e2e covers disjoint vs overlapping edits

## v2.4: Hardening and ergonomics polish

Goal:

- make orchestration robust for day-to-day team use

Status: partially implemented.

Additions:

- improved diagnostics for lock and remote state edge cases
- better defaults around remote selection
- optional terse/human output tuning

Acceptance gates:

- documented runbooks updated
- strict CI templates remain compatible

## Migration and compatibility

- existing `sync` subcommands remain source-compatible
- orchestration mode is additive and becomes the recommended default
- CI can continue to use strict explicit commands or move to `kimen sync --check --json`

## Work order

1. Implement v2.1 command behavior + tests.
2. Update CLI + automation contract docs.
3. Re-run full test + e2e matrix.
4. Iterate on v2.2 conflict intelligence.
