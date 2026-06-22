# Kimen

Kimen is a local secret vault and projection tool.

Store secrets once, then project them into commands, envfiles, rendered files,
or command stdin from small profile files.

Kimen requires [Kvist](https://github.com/kvist-lang/kvist).

## Install

```sh
brew install flakstad/kimen/kimen
```

## Build

```sh
kvist build src/main.kvist
```

## Release

```sh
git tag v2026.6.22
git push origin v2026.6.22
```

Release CI builds macOS, Linux, and Windows archives, publishes checksums, and
updates `flakstad/homebrew-kimen`. The workflow needs `TAP_GITHUB_TOKEN` or
`GORELEASER_GITHUB_TOKEN` with access to that tap.

## Vault

Default vault:

```sh
~/.config/kimen/vault.kv
```

Passphrase lookup order:

```text
KIMEN_PASSPHRASE
session
terminal prompt
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

## Check

```sh
scripts/smoke.sh
```
