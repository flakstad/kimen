#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${KIMEN_BIN:-$ROOT_DIR/dist/kimen}"

if [[ ! -x "$BIN" ]]; then
  echo "error: kimen binary not found at $BIN (run: make build or set KIMEN_BIN)" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

REMOTE_DIR="$TMP_DIR/remote"
IDENTITY_PATH="$TMP_DIR/sync.agekey"
VAULT_A="$TMP_DIR/vault-a.db"
VAULT_B="$TMP_DIR/vault-b.db"
CONFIG_PATH="$TMP_DIR/config.json"

export KIMEN_CONFIG="$CONFIG_PATH"
export KIMEN_PASSPHRASE="pass"

require_exit_code() {
  local expected="$1"
  shift
  set +e
  "$@" >/dev/null 2>"$TMP_DIR/cmd.err"
  local got=$?
  set -e
  if [[ "$got" -ne "$expected" ]]; then
    echo "error: expected exit code $expected, got $got for: $*" >&2
    cat "$TMP_DIR/cmd.err" >&2
    exit 1
  fi
}

assert_contains() {
  local haystack="$1"
  local needle="$2"
  if [[ "$haystack" != *"$needle"* ]]; then
    echo "error: expected output to contain '$needle'" >&2
    echo "output: $haystack" >&2
    exit 1
  fi
}

echo "[e2e-sync] Actor A: init + first push"
export KIMEN_VAULT="$VAULT_A"
"$BIN" vault init >/dev/null
printf 'local-v1' | "$BIN" secret set api_key --stdin >/dev/null
RECIPIENT="$("$BIN" bundle keygen --out "$IDENTITY_PATH" --print-recipient)"
"$BIN" remote add team --path "$REMOTE_DIR" --recipient "$RECIPIENT" --identity "$IDENTITY_PATH" >/dev/null
STATUS_JSON="$("$BIN" sync status --remote team --json)"
assert_contains "$STATUS_JSON" '"can_push":true'
PUSH_DRY_JSON="$("$BIN" sync push --remote team --dry-run --json)"
assert_contains "$PUSH_DRY_JSON" '"action":"sync_push_dry_run"'
"$BIN" sync push --remote team >/dev/null

echo "[e2e-sync] Push lock behavior"
mkdir -p "$REMOTE_DIR"
printf 'held\n' > "$REMOTE_DIR/vault.age.lock"
require_exit_code 32 "$BIN" sync push --remote team
require_exit_code 32 "$BIN" sync push --remote team --dry-run
require_exit_code 32 "$BIN" sync unlock --remote team
"$BIN" sync unlock --remote team --yes >/dev/null
"$BIN" sync push --remote team >/dev/null

printf 'held\n' > "$REMOTE_DIR/vault.age.lock"
touch -t 200001010000 "$REMOTE_DIR/vault.age.lock"
STALE_BREAK_JSON="$("$BIN" sync push --remote team --break-stale-lock-after 30m --json)"
assert_contains "$STALE_BREAK_JSON" '"stale_lock_broken":true'

printf 'held\n' > "$REMOTE_DIR/vault.age.lock"
( sleep 0.2; rm -f "$REMOTE_DIR/vault.age.lock" ) &
"$BIN" sync push --remote team --lock-wait 1s >/dev/null

echo "[e2e-sync] Actor B: mutate remote"
export KIMEN_VAULT="$VAULT_B"
"$BIN" vault init >/dev/null
printf 'remote-v2' | "$BIN" secret set api_key --stdin >/dev/null
"$BIN" bundle seal --vault "$VAULT_B" --out "$REMOTE_DIR/vault.age" --recipient "$RECIPIENT" >/dev/null

echo "[e2e-sync] Actor A: detect conflict + recover"
export KIMEN_VAULT="$VAULT_A"
require_exit_code 31 "$BIN" sync push --remote team
require_exit_code 31 "$BIN" sync push --remote team --dry-run
CONFLICTS_JSON="$("$BIN" sync conflicts --remote team --json)"
assert_contains "$CONFLICTS_JSON" '"has_conflict":true'
assert_contains "$CONFLICTS_JSON" '"reason":"remote_changed"'
PULL_DRY_JSON="$("$BIN" sync pull --remote team --dry-run --json)"
assert_contains "$PULL_DRY_JSON" '"action":"sync_pull_dry_run"'
VALUE_AFTER_PULL_DRY="$("$BIN" secret get api_key --unsafe-stdout)"
if [[ "$VALUE_AFTER_PULL_DRY" != "local-v1" ]]; then
  echo "error: expected local-v1 after pull dry-run, got '$VALUE_AFTER_PULL_DRY'" >&2
  exit 1
fi

PULL_JSON="$("$BIN" sync pull --remote team --json)"
BACKUP_PATH="$(printf '%s' "$PULL_JSON" | sed -n 's/.*"backup_path":"\([^"]*\)".*/\1/p')"
if [[ -z "$BACKUP_PATH" || ! -f "$BACKUP_PATH" ]]; then
  echo "error: expected sync pull backup_path to be set and file to exist" >&2
  echo "$PULL_JSON" >&2
  exit 1
fi

VALUE_AFTER_PULL="$("$BIN" secret get api_key --unsafe-stdout)"
if [[ "$VALUE_AFTER_PULL" != "remote-v2" ]]; then
  echo "error: expected remote-v2 after pull, got '$VALUE_AFTER_PULL'" >&2
  exit 1
fi

echo "[e2e-sync] Restore from backup"
"$BIN" sync restore --backup "$BACKUP_PATH" >/dev/null
VALUE_AFTER_RESTORE="$("$BIN" secret get api_key --unsafe-stdout)"
if [[ "$VALUE_AFTER_RESTORE" != "local-v1" ]]; then
  echo "error: expected local-v1 after restore, got '$VALUE_AFTER_RESTORE'" >&2
  exit 1
fi

echo "[e2e-sync] Reset-baseline safety checks"
require_exit_code 32 "$BIN" sync reset-baseline --remote team --to-remote
"$BIN" sync reset-baseline --remote team --to-remote --yes >/dev/null

echo "[e2e-sync] PASS"
