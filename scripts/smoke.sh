#!/usr/bin/env bash
set -euo pipefail
trap 'echo "smoke failed at line $LINENO: $BASH_COMMAND" >&2' ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXE_SUFFIX="${EXE_SUFFIX:-}"
BIN="$ROOT/dist/kimen$EXE_SUFFIX"
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
SOURCE_PATH="$ROOT/src/main.kvist"
GENERATED_PATH="$ROOT/tmp/main.odin"
BUILD_DIR="$ROOT/tmp"
BIN_OUT="$BIN"
if [[ "$EXE_SUFFIX" == ".exe" ]] && command -v cygpath >/dev/null 2>&1; then
  SOURCE_PATH="$(cygpath -w "$SOURCE_PATH")"
  GENERATED_PATH="$(cygpath -w "$GENERATED_PATH")"
  BUILD_DIR="$(cygpath -w "$BUILD_DIR")"
  BIN_OUT="$(cygpath -w "$BIN_OUT")"
fi
cd "$KVIST_ROOT"
if [[ "$EXE_SUFFIX" == ".exe" && -x "./kvist.exe" && ! -e "./kvist" ]]; then
  cp ./kvist.exe ./kvist
fi
"$KVIST" build "$SOURCE_PATH" --generated "$GENERATED_PATH"
cd "$ROOT"
odin build "$BUILD_DIR" -out:"$BIN_OUT"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

export KIMEN_VAULT="$tmp/vault.kv"
export KIMEN_SESSION="$tmp/session"
export KIMEN_PASSPHRASE="smoke-pass"

"$BIN" vault init >/dev/null
test "$("$BIN" vault path)" = "$KIMEN_VAULT"
printf 'secret-value' | "$BIN" secret set api_key --stdin
"$BIN" vault info | grep -qx 'secrets: 1'

got="$("$BIN" secret get api_key)"
test "$got" = "secret-value"

export KIMEN_NEW_PASSPHRASE="dry-run-pass"
"$BIN" vault rekey --dry-run >/dev/null
unset KIMEN_NEW_PASSPHRASE
printf 'new-smoke-pass\n' | "$BIN" vault rekey --new-passphrase-stdin --no-backup >/dev/null
export KIMEN_PASSPHRASE="new-smoke-pass"
test "$("$BIN" secret get api_key)" = "secret-value"
unset KIMEN_PASSPHRASE
if printf 'smoke-pass\n' | "$BIN" session start --stdin --ttl 1h >/dev/null 2>&1; then
  echo "session accepted old passphrase after rekey" >&2
  exit 1
fi
export KIMEN_PASSPHRASE="new-smoke-pass"

if [[ "$EXE_SUFFIX" == ".exe" ]]; then
  "$BIN" version >/dev/null
  printf 'smoke ok\n'
  exit 0
fi

printf 'env API_KEY=secret:api_key\nfile token.txt=secret:api_key\nenvpath TOKEN_FILE=token.txt\nstdin const:stdin-value\n' > "$tmp/map.kmap"

"$BIN" map lint --map "$tmp/map.kmap" >/dev/null
"$BIN" doctor --map "$tmp/map.kmap" >/dev/null
"$BIN" plan --map "$tmp/map.kmap" >/dev/null
"$BIN" plan --env API_KEY=secret:api_key --file token.txt=secret:api_key --envpath TOKEN_FILE=token.txt --stdin const:stdin-value >/dev/null
printf 'stdin const:a\nstdin const:b\n' > "$tmp/duplicate-stdin.kmap"
if "$BIN" map lint --strict --map "$tmp/duplicate-stdin.kmap" >/dev/null 2>&1; then
  echo "strict lint accepted duplicate stdin mappings" >&2
  exit 1
fi

"$BIN" envfile --map "$tmp/map.kmap" --out "$tmp/out.env"
grep -qx 'API_KEY=secret-value' "$tmp/out.env"
"$BIN" envfile --env API_KEY=secret:api_key --out "$tmp/inline.env"
grep -qx 'API_KEY=secret-value' "$tmp/inline.env"

"$BIN" render --map "$tmp/map.kmap" --dir "$tmp/rendered"
test "$(cat "$tmp/rendered/token.txt")" = "secret-value"
"$BIN" render --file inline-token.txt=secret:api_key --dir "$tmp/inline-rendered"
test "$(cat "$tmp/inline-rendered/inline-token.txt")" = "secret-value"

run_out="$("$BIN" run --map "$tmp/map.kmap" --env EXTRA=const:ok -- sh -c 'printf "%s|%s|%s|%s" "$API_KEY" "$EXTRA" "$(cat "$TOKEN_FILE")" "$(cat)"')"
test "$run_out" = "secret-value|ok|secret-value|stdin-value"

inline_out="$("$BIN" run --env API_KEY=secret:api_key -- sh -c 'printf "%s" "$API_KEY"')"
test "$inline_out" = "secret-value"

inline_all_dir="$tmp/inline-files"
inline_all="$("$BIN" run --files-dir "$inline_all_dir" --file token.txt=secret:api_key --envpath TOKEN_FILE=token.txt --stdin const:stdin-value -- sh -c 'printf "%s|%s|%s" "$KIMEN_FILES_DIR" "$(cat "$TOKEN_FILE")" "$(cat)"')"
test "$inline_all" = "$inline_all_dir|secret-value|stdin-value"

project_out="$("$BIN" project run --env API_KEY=secret:api_key -- sh -c 'printf "%s" "$API_KEY"')"
test "$project_out" = "secret-value"
"$BIN" project render --file project-token.txt=secret:api_key --dir "$tmp/project-rendered"
test "$(cat "$tmp/project-rendered/project-token.txt")" = "secret-value"
"$BIN" project plan --env API_KEY=secret:api_key >/dev/null

printf 'new-smoke-pass\n' | "$BIN" session start --stdin --ttl 1h >/dev/null
unset KIMEN_PASSPHRASE
test "$("$BIN" secret get api_key)" = "secret-value"
"$BIN" session lock >/dev/null

printf 'smoke ok\n'
