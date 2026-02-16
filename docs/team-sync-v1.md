# Team Sync v1

This document defines the Team Sync v1 product surface for Kimen.

## Guarantees

- Local-first runtime: projections always happen locally.
- Ciphertext-only remotes: `sync` transports `vault.age`, never plaintext secret values.
- No remote dependency at runtime: remotes are only needed for sync operations.
- Structured automation contract:
  - canonical JSON + exit codes in `docs/automation-contract.md`
  - sync conflict errors (`31`) include `reason` and `recommended_action`
  - sync lock/precondition errors (`32`) include `reason` and `recommended_action` where available
- Safe preflight modes:
  - `kimen sync pull --dry-run`
  - `kimen sync push --dry-run`
  - both avoid remote writes and baseline mutation

## Supported Operating Models

### 1) Single-Writer (recommended default)

- One writer identity or CI pipeline performs `sync push`.
- Other actors use `sync pull`.
- Lowest operational complexity.

### 2) Coordinated Multi-Writer

- Multiple writers coordinate through baseline checks and lock/strict gating.
- For `fs` remotes, lock files serialize writes.
- For `git` remotes, non-fast-forward rejections plus baseline checks force pull/reconcile/push flow.

## Standard Runbooks

### Preflight Before Deploy

```bash
kimen doctor --strict --json
kimen sync status --remote team --json
kimen sync conflicts --remote team --strict --json
kimen sync pull --remote team --dry-run --json
kimen sync push --remote team --dry-run --json
```

### Normal Sync Loop (writer)

```bash
kimen sync conflicts --remote team --strict --json
kimen sync push --remote team
```

### Conflict (`reason=remote_changed` or `no_local_baseline`)

```bash
kimen sync pull --remote team
# re-apply local changes
kimen sync push --remote team
```

### Remote Disappeared (`reason=remote_disappeared`)

```bash
kimen sync reset-baseline --remote team --to-remote --yes
# or recreate remote bundle, then pull/push as needed
```

### Lock Blocked (`reason=remote_lock_present`)

```bash
# wait/retry
kimen sync push --remote team --lock-wait 30s
# or emergency lock recovery
kimen sync unlock --remote team --yes
```

## CI Gating Pattern

Use strict commands so CI can gate on exit codes without custom JSON parsing:

```bash
kimen doctor --strict --json
kimen sync conflicts --remote team --strict --json
kimen sync push --remote team --dry-run --json
```

Interpretation:

- exit `0`: safe to proceed
- exit `31`: sync conflict; pull/reconcile required
- exit `32`: sync precondition failure (for example, lock present)
