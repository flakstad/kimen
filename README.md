# kimen2

Kimen2 is a simplified Kvist rewrite of Kimen for personal local-first secret
projection.

The rewrite intentionally preserves the commands used by current projects and
drops the old automation-heavy CLI contract.

## Required Surface

- `vault init`
- `vault migrate --from <old-vault> [--to <new-vault>] [--old-bin <path>]`
- `secret set <name> --stdin`
- `secret list`
- `secret get <name> --unsafe-stdout`
- `secret rm <name>`
- `secret mv <old> <new>`
- `run --profile <name> [--env VAR=<value>...] -- <command>...`
- `render --dir <path> --profile <name>`
- `envfile --profile <name> --out <path>`
- `plan --profile <name>`
- `map lint --profile <name> [--strict]`
- `doctor --profile <name> [--strict]`
- `session start/status/lock/stop`

## Observed Existing Usage

Kari and Linje scripts use:

- `vault init`
- `secret set <name> --stdin`
- `secret list`
- `run --profile ... -- ...`
- `run --profile ... --env NAME=const:value -- ...`
- `envfile --profile ... --out ...`
- `map lint --profile ... --strict`
- `doctor --profile ...` and `doctor --profile ... --strict --json`

Gransk uses:

- `kimen run --profile <name> --env GRANSK_KIMEN_ACTIVE=const:1 -- ...`

Manual use requires:

- `session start`
- `session status`
- `session lock`
- `session stop`
- automatic session reuse by vault-opening commands

## Defer Or Drop

- sync/remotes/git remotes
- bundle/age support
- full JSON contracts
- old reason-specific exit codes
- CI scaffold generation
- config unlock methods beyond env/stdin/prompt/session

## Vault Direction

Kimen2 uses a new single-file v2 vault format implemented in Kvist. Existing
Kimen v1 bbolt vaults are migrated with `vault migrate`, which shells out to
the existing Kimen v1 binary for the bbolt read path and writes the results into
the Kvist vault format.

Example:

```sh
KIMEN_PASSPHRASE=... kimen2 vault migrate \
  --from ~/.config/kimen/vault.db \
  --to ~/.config/kimen2/vault.k2v \
  --old-bin ../kimen/dist/kimen
```

## Current Limitations

- `run` uses `os.process_exec`, so child stdout/stderr are captured and replayed
  after the command exits rather than streamed live.
- `stdin` profile mappings are parsed and planned, but not yet projected into
  `run`.
