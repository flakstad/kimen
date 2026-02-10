# Maps and profiles

Kimen supports map files and profiles to avoid repeating long lists of `--env` / `--file` flags.

## Map files (`--map`)

A map file is a comment-friendly, line-oriented projection spec.

Each non-empty line is one of:

- `env VAR=secret.name`
- `file relpath=secret.name`
- `envpath VAR=relpath`

Rules:

- Blank lines and lines starting with `#` are ignored.
- Inline comments are supported: `…  # comment`.
- `envpath` resolves to `$KIMEN_FILES_DIR/<relpath>` (or `<files-dir>/<relpath>` when specified).
- `envpath` is intended to point at a file that is also projected via `file …`.

Example (`.kimen/profiles/linje-prod.kmap`):

```text
# env vars
env LINJE_API_TOKEN=linje.prod.api_token
env MAILERSEND_TOKEN=linje.prod.mailersend_token

# files
file key.json=linje.prod.gcp_sa_key_json
envpath GOOGLE_APPLICATION_CREDENTIALS=key.json
```

Use it:

```bash
kimen run --map .kimen/profiles/linje-prod.kmap -- clojure -M:dev
```

## Profiles (`--profile`)

Profiles are named map files. A profile name resolves to `<name>.kmap` in one of these locations:

1) `$KIMEN_PROFILE_DIR/<name>.kmap`
2) `./.kimen/profiles/<name>.kmap` (relative to the current working directory)
3) `<UserConfigDir>/kimen/profiles/<name>.kmap`

Example:

```bash
kimen run --profile linje-prod -- clojure -M:dev
```

## Precedence and composition

- If both a map/profile and inline flags are provided, Kimen combines them.
- Inline flags are appended after the map, so they naturally “win” for duplicate env vars or file paths.

