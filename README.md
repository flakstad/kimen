# Kimen

Kimen is a local-first tool for bringing secrets into usable runtime form. It stores secrets securely at rest and produces short-lived, context-specific projections—such as environment variables, rendered configuration files, or scoped execution contexts—only when they are intentionally requested.

Kimen treats secrets as *latent capabilities*, not static values: secrets remain inert by default, and only become “real” in a specific form when intent is expressed.

## Status

Kimen is early-stage and evolving. The CLI and on-disk formats may change.

## Mental model

- **Secrets** are encrypted, inert source material.
- **Intent** is an explicit request for a secret *in a particular form*, for a particular use.
- **Projections** are the realized output (env vars, files, exec contexts, etc.) with an explicit lifetime.

## Features (current)

- Local encrypted vault (`vault.db`)
- Projections:
  - `kimen run` (scoped env/files for a single command)
  - `kimen render` (write secret files with strict perms)
  - `kimen envfile` (write `KEY=VALUE` envfiles without printing secrets)
- Repeatable intent: `--map` / `--profile`
- Safe planning: `kimen plan` (no secret values)
- CI/sync primitive: `kimen bundle seal/open` (ciphertext transport via `age`)

## Projections

Kimen’s core abstraction is the **projection**: secrets are inert at rest and only become usable when you explicitly project them into a runtime shape.

Common entrypoints:

- `kimen run` (preferred): run a command with projected env/files
- `kimen render`: render projected files into a directory
- `kimen project ...`: explicit grouped projection commands

See `docs/projections.md`.

## Does something like this already exist?

Short answer, honestly: **this exact thing does not really exist as a coherent tool**.

Pieces exist. The idea exists in fragments. But the system described here — local-first, projection-centric, intent-driven — is genuinely novel *as a whole*.

For a precise, fair map of the landscape and what’s novel about Kimen, see `docs/landscape.md`.

## Local-first, but what about teams and business models?

Kimen is designed to be complete and useful with **no server**. If there’s a business play, it’s in optional coordination (sync, membership, auditing) rather than centralizing runtime or turning Kimen into a hosted vault.

See `docs/business.md`.

## Quickstart (current CLI)

Build and install from source:

```bash
make install
```

Set a vault location (optional) and initialize:

```bash
export KIMEN_VAULT="$HOME/.config/kimen/vault.db"
kimen vault init
```

Add a secret:

```bash
echo -n 'shh' | kimen secret set api_key --stdin
```

Run a command with projected secrets (env + files):

```bash
kimen run --env API_KEY=api_key --file config.txt=api_key -- printenv API_KEY
```

Create a map/profile (optional, recommended for real projects):

```bash
mkdir -p .kimen/profiles
cat > .kimen/profiles/dev.kmap <<'EOF'
env API_KEY=api_key
EOF
kimen run --profile dev -- printenv API_KEY
```

Export/import the vault as an age-encrypted bundle (useful for CI and “no-trust” sync transports):

```bash
kimen bundle keygen --out ci.agekey --print-recipient > ci.agepub
kimen bundle seal --out vault.age --recipient "$(cat ci.agepub)"
kimen bundle open --in vault.age --out-vault "$KIMEN_VAULT" --identity path/to/age.key --overwrite
```

See also `docs/ci-github-actions.md`.

## Roadmap

Ideas and possible future projection types live in `docs/roadmap.md`.

## Docs

- `docs/cli.md`: full CLI/API reference with use-cases and how it works
- `docs/projections.md`: the projection model (why `run`/`render` exist)
- `docs/ci-github-actions.md`: CI pattern using bundles + projections
- `docs/maps.md`: map files and profiles (`--map` / `--profile`)
- `docs/plan-1-2-3.md`: next implementation plan (maps/profiles, plan, envfile)
- `docs/when-to-use.md`: guidance on when Kimen fits (and when it doesn’t)
- `docs/alternatives.md`: adjacent tools and comparisons
- `docs/team-sync.md`: team collaboration models and future direction
- `docs/team-sync-roadmap.md`: team sync roadmap (directional)
