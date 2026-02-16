#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${KIMEN_BIN:-$ROOT_DIR/dist/kimen}"

if [[ ! -x "$BIN" ]]; then
  echo "error: kimen binary not found at $BIN (run: make build or set KIMEN_BIN)" >&2
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "error: git is required for scripts/e2e-sync-git.sh" >&2
  exit 1
fi

# Avoid inheriting git hook environment into nested git commands.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

GIT_REMOTE="$TMP_DIR/team.git"
CONFIG_A="$TMP_DIR/config-a.json"
CONFIG_B="$TMP_DIR/config-b.json"
VAULT_A="$TMP_DIR/vault-a.db"
VAULT_B="$TMP_DIR/vault-b.db"
IDENTITY_PATH="$TMP_DIR/sync.agekey"

export KIMEN_PASSPHRASE="pass"

as_actor_a() {
  export KIMEN_CONFIG="$CONFIG_A"
  export KIMEN_VAULT="$VAULT_A"
}

as_actor_b() {
  export KIMEN_CONFIG="$CONFIG_B"
  export KIMEN_VAULT="$VAULT_B"
}

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

git init --bare "$GIT_REMOTE" >/dev/null

echo "[e2e-sync-git] Actor A: init + first push"
as_actor_a
"$BIN" vault init >/dev/null
printf 'local-v1' | "$BIN" secret set api_key --stdin >/dev/null
RECIPIENT="$("$BIN" bundle keygen --out "$IDENTITY_PATH" --print-recipient)"
"$BIN" remote add team \
  --type git \
  --path "$GIT_REMOTE" \
  --branch main \
  --bundle-path vault.age \
  --recipient "$RECIPIENT" \
  --identity "$IDENTITY_PATH" >/dev/null
STATUS_JSON="$("$BIN" sync status --remote team --json)"
assert_contains "$STATUS_JSON" '"can_push":true'
PUSH_DRY_JSON="$("$BIN" sync push --remote team --dry-run --json)"
assert_contains "$PUSH_DRY_JSON" '"action":"sync_push_dry_run"'
"$BIN" sync push --remote team >/dev/null
require_exit_code 0 "$BIN" sync preflight --remote team --strict --json

echo "[e2e-sync-git] Actor B: pull + mutate + push"
as_actor_b
"$BIN" remote add team \
  --type git \
  --path "$GIT_REMOTE" \
  --branch main \
  --bundle-path vault.age \
  --recipient "$RECIPIENT" \
  --identity "$IDENTITY_PATH" >/dev/null
"$BIN" sync pull --remote team >/dev/null
VALUE_B="$("$BIN" secret get api_key --unsafe-stdout)"
if [[ "$VALUE_B" != "local-v1" ]]; then
  echo "error: expected actor B to read local-v1 after pull, got '$VALUE_B'" >&2
  exit 1
fi
printf 'remote-v2' | "$BIN" secret set api_key --stdin >/dev/null
"$BIN" sync push --remote team >/dev/null

echo "[e2e-sync-git] Actor A: detect conflict + recover"
as_actor_a
require_exit_code 31 "$BIN" sync push --remote team
require_exit_code 31 "$BIN" sync push --remote team --dry-run
require_exit_code 31 "$BIN" sync preflight --remote team --strict --json
CONFLICTS_JSON="$("$BIN" sync conflicts --remote team --json)"
assert_contains "$CONFLICTS_JSON" '"has_conflict":true'
assert_contains "$CONFLICTS_JSON" '"reason":"remote_changed"'
assert_contains "$CONFLICTS_JSON" '"recommended_action":"sync_pull"'
CHANGES_JSON="$("$BIN" sync changes --remote team --json)"
assert_contains "$CHANGES_JSON" '"can_reconcile":true'
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
require_exit_code 0 "$BIN" sync preflight --remote team --strict --json
"$BIN" sync restore --backup "$BACKUP_PATH" >/dev/null
VALUE_AFTER_RESTORE="$("$BIN" secret get api_key --unsafe-stdout)"
if [[ "$VALUE_AFTER_RESTORE" != "local-v1" ]]; then
  echo "error: expected local-v1 after restore, got '$VALUE_AFTER_RESTORE'" >&2
  exit 1
fi

echo "[e2e-sync-git] Reconcile disjoint local/remote changes"
printf 'a-only' | "$BIN" secret set note_local --stdin >/dev/null

as_actor_b
printf 'b-only' | "$BIN" secret set note_remote --stdin >/dev/null
"$BIN" sync push --remote team >/dev/null

as_actor_a
RECONCILE_CHANGES_JSON="$("$BIN" sync changes --remote team --json)"
assert_contains "$RECONCILE_CHANGES_JSON" '"can_reconcile":true'
RECONCILE_PULL_JSON="$("$BIN" sync pull --remote team --reconcile --json)"
assert_contains "$RECONCILE_PULL_JSON" '"action":"sync_pull_reconcile"'
NOTE_LOCAL_AFTER_RECONCILE="$("$BIN" secret get note_local --unsafe-stdout)"
NOTE_REMOTE_AFTER_RECONCILE="$("$BIN" secret get note_remote --unsafe-stdout)"
if [[ "$NOTE_LOCAL_AFTER_RECONCILE" != "a-only" || "$NOTE_REMOTE_AFTER_RECONCILE" != "b-only" ]]; then
  echo "error: expected reconcile to keep note_local=a-only and pull note_remote=b-only" >&2
  exit 1
fi

echo "[e2e-sync-git] Git-specific behavior checks"
require_exit_code 32 "$BIN" sync push --remote team --lock-wait 1s
require_exit_code 32 "$BIN" sync push --remote team --dry-run --lock-wait 1s
git --git-dir "$GIT_REMOTE" update-ref -d refs/heads/main
require_exit_code 31 "$BIN" sync push --remote team
"$BIN" sync reset-baseline --remote team --clear --yes >/dev/null
"$BIN" sync push --remote team >/dev/null

echo "[e2e-sync-git] PASS"
