# Roadmap / ideas

This is a living list of directions Kimen could go, staying aligned with the core invariant:

> Kimen must be complete and useful with no server.

## Potential projection types

Kimen currently supports:

- **env projection** (inject env vars for a child process)
- **file projection** (write secrets to `0600` files under a `0700` dir)
- **exec projection** (`kimen project run`) to scope projections to a single command
- **stdin projection** (write projected bytes to a child process stdin)

Future projections that fit the model:

1) **Systemd-friendly runtime projections**
   - render to a runtime directory (`/run/...`) with strict permissions
   - optionally emit “service wiring” hints (paths, env vars, cleanup)
   - longer term: align with systemd credential patterns

2) **Template/render projections**
   - render structured configs from templates with placeholders resolved from secrets + context
   - keeps glue code out of app/service startup scripts

3) **FD/pipe/stdin projections**
   - deliver secret material via stdin, named pipes, or inherited file descriptors
   - reduces reliance on env vars and persistent files

## Sources beyond stored secrets

Kimen’s mental model is “explicit intent → short-lived projection”. The simplest source of bytes is the local vault, but the same projection model can apply when values are produced on demand.

Examples of sources that still preserve the local-first invariant:

- local `exec:` commands (derive at projection time)
- external secret managers as a *source of truth* (Kimen stays the projection engine)
- minted short-lived credentials (cloud/db) that become inputs to projections

4) **Plan/inspect projections (no materialization)**
   - output *what would happen*: which secrets, which env vars/paths, what lifetime/cleanup
   - great for CI review and “projection diff” workflows

5) **Bundle/recipient projections**
   - better tooling around `bundle seal/open` (rewrap, rotate recipients, verify)
   - still “no-trust transport”: ciphertext sync only

## TUI: when it’s worth building

A TUI is most valuable when it helps with *projection planning and safe operations*, not browsing secret values.

Good TUI use cases:

- manage metadata (names, types, labels, expiry/rotation intent)
- manage projections and profiles/contexts (later)
- show a “projection plan” (what will be created/used/cleaned up) without revealing values
- reduce foot-guns (paths, permissions, lifetime)

Avoid turning the TUI into a “copy secrets” interface by default.

## Suggested next milestones (tight loop)

These are scoped so the CLI remains the primary API and tests stay strong:

1) **Ergonomics + safety**
   - extend typed error codes and `--json` output consistency across any remaining CLI surfaces
   - expand map lint checks (e.g. unreachable mappings, profile composition pitfalls)

2) **Projection planning**
   - `kimen project plan ...` that emits a plan (human + `--json`)
   - optionally: `kimen project run --dry-run` as a wrapper

3) **Systemd-friendly mode**
   - extend the current runtime-dir render mode with stronger unit-file guidance and examples

4) **CI bundles**
   - harden `bundle seal/open` flows for GitHub Actions usage
   - doc templates for common CI patterns

## Parallel track: service UI + landing (separate repo)

This is intentionally separate from the CLI/runtime repo:

- **Marketing/landing site**:
  - public positioning, use cases, docs entrypoints, install/get-started CTA
  - likely static hosting (GitHub Pages or equivalent)
- **Service signup/app shell UI**:
  - auth/onboarding/signup flows for optional hosted coordination
  - should not become a runtime dependency for local projections

Suggested execution:

1) Create separate repo for web surfaces (landing + app shell).
2) Start with static landing + lightweight docs routing.
3) Add signup/onboarding flow stubs once hosted coordination scope is defined.

## Team collaboration (shipped + directional)

Shipped today:

- Team Sync v1/v2 for `fs` and `git` remotes (`remote`, `sync`, `sync init`, `sync preflight`, `sync pull --reconcile`, `sync resolve`).

Directional next tracks:

- **Git-first remotes**: merge-friendly encrypted objects/events that work well with Git, plus tooling for conflicts, locks, and membership changes.
- **Optional hosted coordination**: ciphertext sync + membership + audit aggregation, without hosting plaintext secrets or executing projections.

See `docs/team-sync.md`.
