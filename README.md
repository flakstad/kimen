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

## Examples

```sh
printf '%s' "$API_KEY" | kimen secret set api_key --stdin
kimen session start --ttl 8h
kimen run --env API_KEY=secret:api_key -- sh -c 'curl -H "Authorization: Bearer $API_KEY" https://example.com'
```
