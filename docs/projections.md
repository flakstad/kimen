# Projections

Kimen’s core abstraction is the **projection**: a deterministic, intentional realization of one or more secrets into a runtime shape.

Secrets are encrypted and inert at rest. They only become usable when you explicitly request a projection.

## Built-in projection shapes (today)

- **env**: inject secret values as environment variables for a child process
- **files**: write secret values into `0600` files under a `0700` directory
- **run**: scope projections to a single command execution (`kimen run`)

## CLI entrypoints

Kimen provides short verb commands:

- `kimen run`: run a command with projected env/files
- `kimen render`: render projected files into a directory

The explicit, grouped form is also available:

- `kimen project run`
- `kimen project render`

The grouped `project` commands exist mainly for clarity and future expansion; the preferred everyday UX is `kimen run` / `kimen render`.

## Why projections

Most secret tools treat secrets as values to retrieve. Kimen treats secrets as latent capabilities that should only exist in usable form:

- in the right shape (env/files/etc.)
- for the right scope (a single process, a runtime dir)
- for the right lifetime (ideally just the command execution)

