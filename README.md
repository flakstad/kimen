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

kimen secret set <name>
kimen secret set <name> --stdin
kimen secret list
kimen secret get <name>
kimen secret rm <name>
kimen secret mv <from> <to>

kimen session start
kimen session start --ttl 8h
kimen session status
kimen session lock
kimen session stop

kimen run --env NAME=secret:name -- <command>...
kimen run --profile <name> [--env NAME=value] -- <command>...
kimen render --dir <path> --profile <name>
kimen envfile --profile <name> --out <path>
kimen plan --profile <name>
kimen map lint --profile <name>
kimen doctor --profile <name>
```

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

## Examples

```sh
printf '%s' "$API_KEY" | kimen secret set api_key --stdin
kimen session start --ttl 8h
kimen run --env API_KEY=secret:api_key -- sh -c 'curl -H "Authorization: Bearer $API_KEY" https://example.com'
```
