# Team sync roadmap

This roadmap tracks delivered Team Sync milestones and what comes after v2.

## Invariant

> Projections and runtime behavior are always local.
> Any remote sync layer must be optional for runtime and must not require plaintext secrets.

## Delivered milestones

### Phase 0 (delivered): projection ergonomics foundation

- maps/profiles (`--map`, `--profile`)
- plan/dry-run surfaces (`plan`, `run --dry-run`)
- `envfile` projection
- age bundle transport (`bundle seal/open`)

### Phase 1 (delivered): remote-backed Team Sync v1

- first-class remotes (`remote add/get/set/list/rm`)
- sync status/conflict/push/pull lifecycle
- `fs` + `git` remote backends
- strict conflict/precondition exits for automation (`31`, `32`)
- strict CI gating entrypoint (`sync preflight --strict`)

### Phase 2 (delivered): Team Sync v2 orchestration and reconcile

- orchestration-first default `kimen sync`
- bootstrap command for remote setup + next-step guidance (`sync init`)
- conflict intelligence (`sync changes` with key-level classification)
- guided disjoint reconcile (`sync pull --reconcile`)
- explicit overlapping-key conflict resolution (`sync resolve --take ...`)
- improved remote auto-selection + terse operator output

For implementation-level details and acceptance gates, see `docs/team-sync-v2-plan.md`.

## Directional next phases

### Phase 3 (directional): merge-friendly canonical store

Purpose:

- move beyond whole-vault bundle semantics for stronger multi-writer collaboration

Possible direction:

- append-only encrypted events and/or per-replica shards
- deterministic replay into derived local state
- explicit conflict tools with inspectable history

### Phase 4 (directional): optional hosted coordination

Purpose:

- improve team UX without changing local runtime guarantees

Possible scope:

- ciphertext-only storage/coordination
- membership/key exchange assistance
- lock/lease and audit aggregation for larger teams

Non-goal:

- hosted plaintext runtime secret execution

## Near-term execution focus

- complete docs/API parity for all shipped sync behavior (README + docs)
- keep fs/git sync e2e coverage green (`make sync-e2e-all`)
- preserve automation contract stability as sync ergonomics evolve
