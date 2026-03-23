# Maps and profiles

Kimen supports map files and profiles to avoid repeating long lists of `--env` / `--file` flags.
A map file is the projection spec itself. A profile is a name that resolves to a map file.

## Map files (`--map`)

A map file is a comment-friendly, line-oriented projection spec. By convention, these files use the `.kmap` extension.

Each non-empty line is one of:

- `env VAR=<value>`
- `file relpath=<value>`
- `stdin <value>`
- `envpath VAR=relpath`

Rules:

- Blank lines and lines starting with `#` are ignored.
- Inline comments are supported: `…  # comment`.
- `envpath` resolves to `$KIMEN_FILES_DIR/<relpath>` (or `<files-dir>/<relpath>` when specified).
- `envpath` is intended to point at a file that is also projected via `file …`.
- `<value>` is usually a vault secret name, but can also be an alternate source:
  - `secret:<name>`: explicit secret reference (equivalent to bare secret name)
  - `const:<literal>`: inline literal bytes (no vault lookup)
  - `exec:<command...>`: run a local command and use its stdout (with one trailing newline stripped)
    - Note: arguments are split on whitespace (no shell parsing/quoting). Use a wrapper script for complex cases.

Example (`.kimen/profiles/linje-prod.kmap`):

```text
# env vars
env LINJE_API_TOKEN=linje.prod.api_token
env MAILERSEND_TOKEN=linje.prod.mailersend_token
env PORT=const:6060

# files
file key.json=linje.prod.gcp_sa_key_json
envpath GOOGLE_APPLICATION_CREDENTIALS=key.json

# stdin (example)
stdin exec:gcloud auth print-access-token
```

Use it:

```bash
kimen run --map .kimen/profiles/linje-prod.kmap -- clojure -M:dev
```

## Profiles (`--profile`)

Profiles are named map files. For example, `--profile linje-prod` resolves to `linje-prod.kmap` in one of these locations:

1) `$KIMEN_PROFILE_DIR/<name>.kmap`
2) `./.kimen/profiles/<name>.kmap` (relative to the current working directory)
3) `<UserConfigDir>/kimen/profiles/<name>.kmap`

Profile names are restricted to: letters, digits, `.`, `_`, and `-`.

Example:

```bash
kimen run --profile linje-prod -- clojure -M:dev
```

## Precedence and composition

- If both a map/profile and inline flags are provided, Kimen combines them.
- Inline flags are appended after the map, so they naturally “win” for duplicate env vars or file paths.

## Linting maps

Use map linting to catch common mistakes before running projections:

```bash
kimen map lint --map .kimen/profiles/linje-prod.kmap
kimen map lint --profile linje-prod --json
kimen map lint --profile linje-prod --strict
kimen map lint --profile linje-prod --mode envfile
```

`--mode` can be used to scope mode-specific warnings:

- `all` (default): include cross-mode warnings
- `run`: suppress warnings that only apply to render/envfile flows
- `render`: include warnings relevant to render behavior
- `envfile`: include warnings relevant to envfile behavior

Current lint checks:

- empty map files (no effective mappings) as errors
- duplicate mappings with conflicting targets (`env`, `file`, `envpath`) as errors
- redundant duplicate mappings with identical targets as warnings
- `envpath` vars that shadow `env` vars with the same name as warnings
- `envpath` entries that reference missing `file` projections as errors
- projected file path/directory conflicts as errors (for example `file creds` and `file creds/token`)
- mode-specific warnings (for example `stdin` is run-only; `envpath` requires `--files-dir` for `envfile`)
- warnings when a map has only file mappings (so `kimen envfile` would fail)
- warnings for shell-sensitive `exec:` specs that may require wrapper scripts
- warnings for profile shadowing when multiple profile candidates exist but precedence chooses one

Lint exit behavior:

- exits `0` when there are no lint errors (warnings are allowed)
- with `--strict`, warnings are treated as failures
- exits `20` when lint errors are present
