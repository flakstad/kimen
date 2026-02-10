# Roadmap / ideas

This is a living list of directions Kimen could go, staying aligned with the core invariant:

> Kimen must be complete and useful with no server.

## Potential projection types

Kimen currently supports:

- **env projection** (inject env vars for a child process)
- **file projection** (write secrets to `0600` files under a `0700` dir)
- **exec projection** (`kimen project run`) to scope projections to a single command

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
   - `kimen secret rm`
   - `kimen secret mv` (or `rename`)
   - better error codes and `--json` output for more commands

2) **Projection planning**
   - `kimen project plan ...` that emits a plan (human + `--json`)
   - optionally: `kimen project run --dry-run` as a wrapper

3) **Systemd-friendly mode**
   - a command that renders to a chosen runtime dir with predictable naming/permissions
   - document recommended unit file patterns

4) **CI bundles**
   - harden `bundle seal/open` flows for GitHub Actions usage
   - doc templates for common CI patterns

