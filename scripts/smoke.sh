#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXE_SUFFIX="${EXE_SUFFIX:-}"
BIN="$ROOT/dist/kimen$EXE_SUFFIX"
OLD_BIN="${OLD_KIMEN_BIN:-$ROOT/../kimen-go/dist/kimen}"
KVIST_ROOT="${KVIST_ROOT:-}"
if [[ -z "$KVIST_ROOT" ]]; then
  for candidate in "$ROOT/../kvist" "$ROOT/../../../kvist"; do
    if [[ -x "$candidate/kvist" || -x "$candidate/kvist.exe" ]]; then
      KVIST_ROOT="$candidate"
      break
    fi
  done
fi
KVIST="${KVIST:-./kvist$EXE_SUFFIX}"

if [[ -z "$KVIST_ROOT" ]]; then
  echo "KVIST_ROOT is required" >&2
  exit 1
fi

cd "$ROOT"
mkdir -p dist tmp
cd "$KVIST_ROOT"
"$KVIST" build "$ROOT/src/main.kvist" --generated "$ROOT/tmp/main.odin" >/dev/null
cd "$ROOT"
odin build "$ROOT/tmp" -out:"$BIN"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

export KIMEN_VAULT="$tmp/vault.kv"
export KIMEN_SESSION="$tmp/session"
export KIMEN_PASSPHRASE="smoke-pass"

"$BIN" vault init >/dev/null
printf 'secret-value' | "$BIN" secret set api_key --stdin

got="$("$BIN" secret get api_key)"
test "$got" = "secret-value"

printf 'env API_KEY=secret:api_key\nfile token.txt=secret:api_key\nenvpath TOKEN_FILE=token.txt\nstdin const:stdin-value\n' > "$tmp/map.kmap"

"$BIN" map lint --map "$tmp/map.kmap" >/dev/null
"$BIN" doctor --map "$tmp/map.kmap" >/dev/null
"$BIN" plan --map "$tmp/map.kmap" >/dev/null

"$BIN" envfile --map "$tmp/map.kmap" --out "$tmp/out.env"
grep -qx 'API_KEY=secret-value' "$tmp/out.env"

"$BIN" render --map "$tmp/map.kmap" --dir "$tmp/rendered"
test "$(cat "$tmp/rendered/token.txt")" = "secret-value"

run_out="$("$BIN" run --map "$tmp/map.kmap" --env EXTRA=const:ok -- sh -c 'printf "%s|%s|%s|%s" "$API_KEY" "$EXTRA" "$(cat "$TOKEN_FILE")" "$(cat)"')"
test "$run_out" = "secret-value|ok|secret-value|stdin-value"

inline_out="$("$BIN" run --env API_KEY=secret:api_key -- sh -c 'printf "%s" "$API_KEY"')"
test "$inline_out" = "secret-value"

printf 'smoke-pass\n' | "$BIN" session start --stdin --ttl 1h >/dev/null
unset KIMEN_PASSPHRASE
test "$("$BIN" secret get api_key)" = "secret-value"
"$BIN" session lock >/dev/null

if [[ -x "$OLD_BIN" ]]; then
  old_vault="$tmp/old.db"
  new_vault="$tmp/migrated.kv"
  KIMEN_VAULT="$old_vault" KIMEN_PASSPHRASE="old-pass" "$OLD_BIN" vault init >/dev/null
  printf 'old-secret' | KIMEN_VAULT="$old_vault" KIMEN_PASSPHRASE="old-pass" "$OLD_BIN" secret set old_key --stdin >/dev/null
  KIMEN_PASSPHRASE="old-pass" "$BIN" vault migrate --from "$old_vault" --to "$new_vault" --old-bin "$OLD_BIN" >/dev/null
  test "$(KIMEN_VAULT="$new_vault" KIMEN_PASSPHRASE="old-pass" "$BIN" secret get old_key)" = "old-secret"
fi

printf 'smoke ok\n'
