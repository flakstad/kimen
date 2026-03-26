# Stability, formats, and upgrades (early-stage)

Kimen is early-stage. The CLI surface and on-disk formats may change.

This doc sets expectations so adopters know what is likely to be stable and what may churn.

## What may change

- CLI flags and subcommands (especially before 1.0)
- Vault/bundle format details (with migrations or export/import paths)
- Map/profile file format (new directives may be added)
- Team sync data/metadata semantics (while preserving documented automation contracts)

## What we try to preserve

- Safe defaults (no secret values printed by default)
- Projection-first workflows (`run`, `render`, `envfile`, `plan`)
- A path to get your data out (export/import, backups)

## Backups (recommended)

For now, the simplest backup story is:

- back up the vault file (`vault.db`) and/or
- use a ciphertext transport (`bundle seal`) and store the resulting bundle in a safe place

Before upgrading across versions, consider keeping a copy of:

- your vault file(s)
- your profiles/maps (`.kimen/profiles/*.kmap`)

## Upgrade policy (current)

Until Kimen declares a 1.0:

- expect breaking changes occasionally
- expect docs to describe migration/export options when formats change
