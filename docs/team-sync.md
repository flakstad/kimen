# Team sync and collaboration

Kimen is local-first and complete without a server. Teams still need shared state. This document describes collaboration models and where Kimen could go.

## The invariant

> Runtime behavior is always local: projections happen on the machine running the command or service.

“Team sync” should not become “central runtime dependency”.

## Today (works now): bundles + shared transport

Kimen supports transporting a vault as ciphertext:

- `kimen bundle seal`: encrypt `vault.db` into `vault.age`
- `kimen bundle open`: decrypt `vault.age` into a local vault file

This enables:

- CI: store `vault.age` in a repo/artifact store; decrypt inside the job
- Small teams: share `vault.age` through any channel

Limitations:

- Whole-vault transport is coarse.
- Concurrent edits don’t merge cleanly.

## Team models (in increasing capability)

### 1) Single-writer shared vault

One operator/CI job is the writer. Everyone else consumes projections.

Pros:

- Simple and safe
- Easy to automate

Cons:

- Not collaborative for writes

### 2) Locking over whole-vault bundles

Keep whole-vault bundles, but add coordination (a lease/lock) so only one writer updates at a time.

Pros:

- Avoids merge semantics

Cons:

- Serializes edits
- Requires a lock mechanism (Git-based or hosted)

### 3) Merge-friendly canonical store (Git-style collaboration)

Adopt a merge-friendly canonical format (e.g. append-only encrypted events, per-device shards), so Git can merge and clients can deterministically replay.

Pros:

- Real multi-writer collaboration without a trusted server

Cons:

- Requires a new storage format and membership/key management story

### 4) Hosted sync & coordination (optional)

A hosted service can remain “no-trust” (ciphertext only) and still provide:

- membership
- key exchange assistance
- conflict resolution/coordination
- audit aggregation

Pros:

- Best UX for teams
- Doesn’t require hosting plaintext secrets or executing projections

Cons:

- You operate a service; teams depend on it for sync (not runtime)

## Recommended direction

Kimen should ideally support multiple collaboration backends:

- Git-first, self-managed remotes (for teams that want to own transport)
- Optional hosted coordination (for teams that want the best UX)

Both can preserve the local-first core if the server never sees plaintext secrets and never participates in runtime projections.

