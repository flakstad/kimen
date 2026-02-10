# Plan: maps/profiles, plan/dry-run, envfile

This document describes the next implementation sequence:

1) Map files + profiles
2) Plan / dry-run
3) Envfile (dotenv) projection

The goal is to improve ergonomics for real projects (Linje, Kari, Ro, CI) while preserving Kimen’s core invariant:

> Kimen must be complete and useful with no server.

## 1) Map files + profiles

### Problem

Users don’t want to repeat long lists of `--env` and `--file` flags for common workflows (e.g. “kari-dev”, “linje-prod ops”, “CI deploy”).

### Outcome

- `kimen run --map <file> -- <cmd>`
- `kimen render --map <file> --dir <out>`
- `kimen envfile --map <file> --out <path>` (added in step 3)

Profiles are named pointers to maps:

- `kimen run --profile linje-prod -- <cmd>`
- Profiles stay local-first: they resolve to local files (repo-local and/or user-local).

### Format guidance (initial)

We want:

- comment support
- easy diffs and reviews
- no secret values, only references to secret names
- trivial parsing and clear errors

Recommended v0 approach: a **line-oriented map format** (Kimen-specific), e.g.:

```
# env mappings
env LINJE_API_TOKEN=linje.prod.api_token
env MAILERSEND_TOKEN=linje.prod.mailersend_token

# file mappings
file config/creds.edn=linje.prod.creds_edn

# env var pointing at a projected file
envpath GOOGLE_APPLICATION_CREDENTIALS=key.json
```

This can be extended later without committing to EDN/JSON/YAML semantics.

### Open questions (to finalize before implementation)

- Where do profiles live?
  - repo-local (checked in), user-local (config dir), or both
- Should `--map` accept multiple files (merge/override)?
- Should maps support grouping/namespacing (for `kimen plan`)?

## 2) Plan / dry-run

### Problem

Operators and CI need confidence: “what will materialize?”, without leaking values.

### Outcome

Add a plan mode that never prints secret values:

- `kimen plan --map <file>`
- `kimen run --dry-run --map <file> -- <cmd>` (optional wrapper)

Outputs:

- secret names referenced (not values)
- env var names that would be set
- file paths that would be written
- permissions (`0700` dirs, `0600` files)
- whether temp dirs are used and cleaned up

Support `--json` for safe scripting.

## 3) Envfile (dotenv) projection

### Problem

Systemd and deploy tooling often want a stable `EnvironmentFile=` (or a “dotenv” file) without printing values to stdout.

### Outcome

- `kimen envfile --out /path/to/app.env --map <file>`
- (and/or) `kimen envfile --out ... --env VAR=secretName ...` (parity with existing flags)

Constraints:

- safe output perms (`0600`) + atomic write
- predictable ordering (for diffs)
- strict validation:
  - reject NUL/newlines in values
  - handle quoting/escaping robustly (dotenv vs systemd environment file quirks)

Optional: emit a non-secret hint (e.g. `EnvironmentFile=/path/to/app.env`) behind a flag, never by default.

## Non-goals (for this sequence)

- A required server or sync backend
- Runtime daemon required for normal usage
- “Unlock agent” / keychain integration (valuable, but a separate milestone)
- Full templating language (if templating is added later, keep it intentionally constrained)

## Next: team collaboration

After this sequence, the next “big decision” is team collaboration.

See `docs/team-sync.md` for models and tradeoffs (bundles, Git-first, hosted coordination).
