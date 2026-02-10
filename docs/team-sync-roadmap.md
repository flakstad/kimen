# Team sync roadmap (directional)

This document turns `docs/team-sync.md` into an actionable roadmap.

Goal: strong team collaboration while preserving Kimen’s core invariant:

> Projections and runtime behavior are always local.
> Any “remote” must not be required to run a command, and must not need plaintext secrets.

Kimen should ideally support multiple collaboration backends:

- **Git-first / self-managed** remotes (teams bring their own Git hosting or storage)
- **Optional hosted coordination** (best UX, still ciphertext-only)

## Where we are today

Kimen has:

- local vault (`vault.db`) encrypted at rest
- projections (`run`, `render`, `envfile`) + planning (`plan`)
- bundle transport (`bundle seal/open`) using `age`

This supports CI and small-team workflows, but whole-vault transport is coarse and doesn’t merge well under concurrent edits.

## Phase 0 (done): make intent repeatable and safe

Status: implemented.

- maps/profiles (`--map`, `--profile`)
- `plan` / `run --dry-run` (no values)
- `envfile` projection (atomic write, strict validation)
- bundles for CI and “no-trust” transport

## Phase 1: introduce a first-class “remote” concept (without redesigning storage)

Purpose: automate the currently-manual push/pull loop, while keeping the storage format unchanged.

### CLI surface (proposed)

- `kimen remote add <name> …`
- `kimen remote list`
- `kimen sync status [--remote <name>]`
- `kimen sync push [--remote <name>]`
- `kimen sync pull [--remote <name>]`

### Backend types (proposed)

- **git**: store `vault.age` in a repo (ciphertext); manage push/pull via git
- **fs**: store `vault.age` in a shared folder (Syncthing/Dropbox/etc.)
- (later) **s3/http**: store bundles in a blob store (still ciphertext-only)

### Concurrency model (phase 1)

Choose one explicit model to avoid footguns:

1) **single-writer** (recommended v1 default)
   - one operator/CI pipeline owns updates
   - everyone else is read-only consumer

2) **lock/lease** (optional)
   - before pushing, acquire a lock (implemented in the remote)
   - avoids merge semantics while supporting multiple writers (serialized)

This phase should also add safe mechanics for updating the local vault:

- pull → verify bundle decrypts → atomic swap of local vault file

## Phase 2: merge-friendly canonical store (Git-native multi-writer)

Purpose: enable collaborative writes without requiring locks or a central service.

This requires a new canonical storage model designed for merges. A proven pattern is:

- **append-only encrypted events**
- **per-replica shards** (each device appends to its own log file to minimize conflicts)
- deterministic replay into a derived local index for fast reads

### Outcomes (phase 2)

- Git merges become “normal”: multiple writers can push concurrently
- local rebuild is possible: delete derived state and replay from events
- conflict tooling can be explicit and inspectable

### Key design decisions (phase 2)

- **Object model**
  - per-secret objects vs append-only events
  - how deletes/renames are represented

- **Encryption and metadata**
  - which fields are plaintext (e.g. secret names) vs encrypted
  - versioned record format for forward compatibility

- **Membership changes**
  - how recipients/devices are managed
  - rewrap/rotation operations when membership changes

## Phase 3: optional hosted coordination (ciphertext-only)

Purpose: offer the best UX for teams that don’t want to self-manage Git workflows.

This is not “Kimen in the cloud”. It is coordination:

- ciphertext blob/object storage
- membership management + key exchange assistance
- locks/leases (if needed)
- optional audit aggregation (non-secret metadata)

The service should be:

- optional and replaceable (self-hostable or bring-your-own)
- never a runtime dependency (projections always local)
- never a plaintext secret holder

## What we should build next

If we want team usage soon without overbuilding, the next practical step is Phase 1:

- a remote abstraction
- `sync push/pull/status`
- Git backend for ciphertext bundles
- single-writer defaults (plus optional locks later)

Then we can design Phase 2 deliberately using patterns already proven in other local-first systems.

