# Team sync and collaboration

Kimen is local-first and complete without a server. Teams still need shared state, conflict handling, and automation-safe coordination.

This document describes the shipped Team Sync surface and what remains directional.

## The invariant

> Runtime behavior is always local: projections happen on the machine running the command or service.

Team sync should never become a required runtime control plane.

## Shipped Surface

Kimen ships first-class team sync for `fs` and `git` remotes:

- `kimen remote add/get/set/list/rm`
- orchestration-first `kimen sync` (with `--check`, `--dry-run`, `--json`, `--terse`)
- explicit subcommands for operators/automation:
  - `sync init`
  - `sync preflight`
  - `sync status`
  - `sync conflicts`
  - `sync changes`
  - `sync pull` / `sync push`
  - `sync resolve`
  - `sync reset-baseline` / `sync unlock` / `sync restore`

Current safety model:

- ciphertext-only transport (`vault.age`) over remote storage
- runtime still local even when sync is configured
- typed conflict/precondition exits (`31` and `32`)
- strict CI gating with `sync preflight --strict`
- no-mutation preflight via `sync pull --dry-run` and `sync push --dry-run`

Current conflict model:

- baseline rev checks detect remote drift before push
- disjoint local+remote key changes can reconcile safely (`sync pull --reconcile`)
- overlapping key changes require explicit operator intent (`sync resolve --take local|remote`)

## Supported operating models

### 1) Single-writer (recommended default)

One operator/CI pipeline pushes; everyone else pulls.

Pros:

- lowest operational complexity
- predictable CI behavior

Cons:

- write throughput is serialized

### 2) Coordinated multi-writer

Multiple writers coordinate using status/conflict checks plus reconcile/resolve flow.

Pros:

- supports multiple team editors without centralized runtime service

Cons:

- requires stronger operational discipline

## CI guidance

For gating deployments and pipelines, use:

```bash
kimen sync preflight --remote team --strict --json
```

Interpretation:

- exit `0`: safe to proceed
- exit `31`: sync conflict (pull/reconcile/resolve required)
- exit `32`: non-conflict precondition failure (lock/missing config/other blockers)

## What remains directional

These are not part of the current shipped sync model:

- merge-friendly canonical storage redesign (beyond whole-vault bundle transport)
- optional hosted coordination service (ciphertext-only)
- richer membership/key-management UX for larger organizations

## Related docs

- `docs/team-sync-v1.md`: v1 guarantees and runbooks
- `docs/automation-contract.md`: canonical JSON and exit-code contract
- `docs/team-sync-roadmap.md`: delivered milestones and directional next phases
