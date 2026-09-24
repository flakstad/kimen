# Kimen

Kimen is a local secret vault and projection tool.

Store secrets once, then project them into commands, envfiles, rendered files,
or command stdin from small profile files.

## Install

Download [latest release](https://github.com/flakstad/kimen/releases), or
install via brew.

```sh
brew install flakstad/kimen/kimen
```

## Build

Kimen requires [Kvist](https://github.com/kvist-lang/kvist) to build from source.

```sh
kvist build src/main.kvist
```

## Vault

Default vault:

```sh
~/.config/kimen/vault.kv
```

Use another vault with `--vault <path>`:

```sh
kimen vault init --vault ~/.config/kimen/work.kv
kimen secret list --vault ~/.config/kimen/work.kv
```

Or set it for a shell:

```sh
export KIMEN_VAULT=~/.config/kimen/work.kv
kimen secret list
```

## Commands

```sh
kimen vault init
kimen vault path
kimen vault info
kimen vault rekey
kimen vault rekey --passphrase-cmd <cmd>
kimen vault rekey --dry-run
kimen vault rekey --backup-dir <path>

kimen secret set <name>
kimen secret set <name> --stdin
kimen secret list
kimen secret get <name> --unsafe-stdout
kimen secret rm <name>
kimen secret mv <from> <to>

kimen session start
kimen session start --ttl 8h
kimen session start --passphrase-cmd <cmd>
kimen session status
kimen session lock
kimen session stop

kimen sync status
kimen sync push [--dry-run]
kimen sync pull [--dry-run] [--force]
kimen sync auto on|off|status

kimen run [source] [projection...] -- <command>...
kimen render (--dir <path>|--systemd-service <name>) [source] [--file path=value]
kimen envfile --out <path> [source] [--env NAME=value]
kimen plan [source] [projection...]
kimen map lint (--profile <name>|--map <path>) [--strict]
kimen doctor (--profile <name>|--map <path>) [--strict]
```

`source` is `--profile <name>` or `--map <path>`.

`projection` is `--env NAME=value`, `--file path=value`,
`--envpath NAME=path`, or `--stdin value`.

Use `--passphrase-cmd <cmd>` when a script should unlock the vault
non-interactively. `render --systemd-service <name>` writes files under
`/run/kimen/<name>`; use `--runtime-dir <path>` to choose another base.

## Profiles

Profiles are `.kmap` files:

```text
env NAME=secret:name
env DATABASE_URL=prod.database_url
env MODE=const:dev
file token.txt=secret:api_token
envpath TOKEN_FILE=token.txt
stdin secret:request_body
```

Mapping values are vault keys by default. Prefix a value with `const:` for a
literal value, `secret:` for an explicit vault key, or `exec:` to read a value
from a command.

Profile lookup:

```text
.kimen/profiles/<name>.kmap
$XDG_CONFIG_HOME/kimen/profiles/<name>.kmap
~/.config/kimen/profiles/<name>.kmap
```

Use `--map <path>` to pass a map file directly.

`kimen doctor` checks that the map is valid, the vault can be opened, and every
secret referenced by the map exists.

## Encrypted vault sync

Kimen can synchronize the existing encrypted `vault.kv` through Git. The
remote receives the vault file byte-for-byte; Kimen never decrypts it during a
sync, and neither the passphrase, plaintext values, nor secret names are sent
to Git. The vault format is unchanged.

The remote is selected in this order:

1. `--remote <git-url>`
2. `KIMEN_SYNC_REMOTE`
3. the remote saved locally by `sync auto on`, or the last successful push or pull

The branch is selected from `--branch`, `KIMEN_SYNC_BRANCH`, the saved local
configuration, or `main`. Authentication is handled by Git itself, so SSH
keys, a credential helper, or `gh auth setup-git` work normally.

### Two-machine setup

Create an empty private repository, for example `flakstad/kimen-vault`. With
the GitHub CLI:

```sh
gh repo create flakstad/kimen-vault --private
```

On machine A, where the vault already exists:

```sh
export KIMEN_SYNC_REMOTE=git@github.com:flakstad/kimen-vault.git

kimen sync status --json
kimen sync push --dry-run
kimen sync push
kimen sync auto on
```

The first push is accepted only when the remote branch has no `vault.kv`.
After a successful push, the remote and branch are saved in
`~/.config/kimen/vault.kv.sync`, so the environment variable is optional for
later commands.

On machine B, configure the same remote and pull before creating or editing a
local vault:

```sh
export KIMEN_SYNC_REMOTE=git@github.com:flakstad/kimen-vault.git

kimen sync status
kimen sync pull --dry-run
kimen sync pull
kimen sync auto on
```

Machine B now has the same encrypted `~/.config/kimen/vault.kv`. Supply the
vault passphrase locally when using secrets; sync neither needs nor transfers
it.

With automatic sync enabled, normal Kimen commands synchronize at command
boundaries. No explicit pull or push is needed:

```sh
kimen secret set prod.api_token --stdin
kimen secret get prod.api_token --unsafe-stdout
```

Before a vault command, Kimen checks the remote. It pulls when the remote is
ahead and pushes any locally pending version when the local vault is ahead.
After `secret set`, `secret rm`, `secret mv`, `vault init`, or `vault rekey`, it
immediately attempts another push. `run`, `render`, `envfile`, `doctor`, vault
reads, secret reads, and session start also reconcile pending changes before
opening the vault.

Automatic sync is deliberately offline-friendly. If Git cannot reach the
remote, reads continue from the local vault and mutations are saved locally.
The mutation prints a warning that its push is pending but still succeeds. The
next vault command with connectivity pushes the pending ciphertext. A real
local/remote divergence remains a conflict: write commands stop with exit 31,
while read commands warn and use the local vault.

Use `--no-sync` for a single command that must not contact the remote, or set
`KIMEN_SYNC_AUTO=0` to temporarily override the saved setting. Conversely,
`KIMEN_SYNC_AUTO=1` enables automatic mode from the environment when a remote
is available through `KIMEN_SYNC_REMOTE` or saved state.

```sh
kimen secret get api_token --unsafe-stdout --no-sync
kimen sync auto off
```

Each successful sync stores SHA-256 baselines for the local and remote
ciphertext. A push refuses to overwrite a remote vault it has not seen, and a
Git push is never forced, so a remote update racing with the operation is also
rejected. A pull refuses to replace a locally changed vault. Resolve that by
keeping or moving the local vault aside, or explicitly choose the remote copy:

```sh
kimen sync pull --force
```

Every pull that replaces an existing vault first creates a mode-0600 backup
named like `vault.kv.backup.<timestamp>`. Both the backup and final vault/state
writes use same-directory temporary files followed by atomic rename. All
ordinary vault mutations use the same atomic-write primitive. If an operation
is interrupted after the vault or remote was updated but before the baseline
was saved, rerunning the same push or pull recognizes matching ciphertext and
repairs the baseline without another replacement.

`--dry-run` performs the remote checks and conflict detection without changing
the vault, baseline, or Git repository. `--json` writes one JSON object to
stdout. The JSON includes only sync metadata and paths, never decrypted vault
content.

Sync exit codes are stable for scripts:

- `0`: success, no-op, or actionable status without a conflict
- `2`: invalid usage or no configured remote
- `30`: Git/remote access failure
- `31`: conflict requiring an explicit choice
- `32`: local I/O or sync precondition failure

For example:

```sh
if result="$(kimen sync push --json)"; then
  printf '%s\n' "$result"
else
  code=$?
  printf '%s\n' "$result"
  exit "$code"
fi
```

## Examples

```sh
printf '%s' "$API_KEY" | kimen secret set api_key --stdin
kimen session start --ttl 8h
kimen run --env API_KEY=secret:api_key -- sh -c 'curl -H "Authorization: Bearer $API_KEY" https://example.com'
```

## License

Kimen is licensed under the MIT License. See [LICENSE](LICENSE).
