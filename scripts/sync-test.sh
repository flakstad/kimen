#!/usr/bin/env bash

set -euo pipefail
trap 'echo "sync test failed at line $LINENO: $BASH_COMMAND" >&2' ERR

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="${1:-$ROOT/dist/kimen${EXE_SUFFIX:-}}"

if ! command -v git >/dev/null 2>&1; then
  echo "git is required for sync tests" >&2
  exit 1
fi

expect_code() {
  local expected="$1"
  shift
  set +e
  "$@" >/dev/null 2>&1
  local actual=$?
  set -e
  if [[ "$actual" -ne "$expected" ]]; then
    echo "expected exit $expected, got $actual: $*" >&2
    exit 1
  fi
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

remote="$tmp/kimen-vault.git"
vault_a="$tmp/machine-a/vault.kv"
vault_b="$tmp/machine-b/vault.kv"
export KIMEN_PASSPHRASE="sync-test-passphrase"

git init --bare -q "$remote"

# First push: dry-run is inert, then the encrypted vault is published.
KIMEN_VAULT="$vault_a" "$BIN" vault init >/dev/null
printf 'first-value' | KIMEN_VAULT="$vault_a" "$BIN" secret set api_key --stdin >/dev/null
dry_json="$(KIMEN_VAULT="$vault_a" "$BIN" sync push --remote "$remote" --dry-run --json)"
grep -q '"action":"sync_push_dry_run"' <<<"$dry_json"
if git --git-dir="$remote" show-ref --verify --quiet refs/heads/main; then
  echo "dry-run created the remote branch" >&2
  exit 1
fi
KIMEN_VAULT="$vault_a" "$BIN" sync push --remote "$remote" >/dev/null
git --git-dir="$remote" show main:vault.kv | cmp - "$vault_a"
if git --git-dir="$remote" show main:vault.kv | grep -Eq 'api_key|first-value'; then
  echo "remote exposed plaintext or a secret name" >&2
  exit 1
fi

# A new machine can pull without possessing a local vault or passphrase.
unset KIMEN_PASSPHRASE
pull_json="$(KIMEN_VAULT="$vault_b" "$BIN" sync pull --remote "$remote" --json)"
grep -q '"status":"pulled"' <<<"$pull_json"
cmp "$vault_a" "$vault_b"
test -f "$vault_b.sync"

# Divergence: push and pull both refuse; explicit --force pull backs up first.
export KIMEN_PASSPHRASE="sync-test-passphrase"
printf 'from-machine-b' | KIMEN_VAULT="$vault_b" "$BIN" secret set remote_only --stdin >/dev/null
KIMEN_VAULT="$vault_b" "$BIN" sync push >/dev/null
printf 'from-machine-a' | KIMEN_VAULT="$vault_a" "$BIN" secret set local_only --stdin >/dev/null
expect_code 31 env KIMEN_VAULT="$vault_a" "$BIN" sync push
expect_code 31 env KIMEN_VAULT="$vault_a" "$BIN" sync pull
expect_code 31 env KIMEN_VAULT="$vault_a" "$BIN" sync pull --dry-run
cp "$vault_a" "$tmp/local-before-force.kv"
force_json="$(KIMEN_VAULT="$vault_a" "$BIN" sync pull --force --json)"
backup_path="$(sed -n 's/.*"backup_path":"\([^"]*\)".*/\1/p' <<<"$force_json")"
test -n "$backup_path"
test -f "$backup_path"
[[ "$backup_path" == "$vault_a.backup."* ]]
cmp "$backup_path" "$tmp/local-before-force.kv"
cmp "$vault_a" "$vault_b"

# Recovery after interruption: the vault was replaced but state was not updated.
printf 'second-remote-change' | KIMEN_VAULT="$vault_b" "$BIN" secret set remote_second --stdin >/dev/null
KIMEN_VAULT="$vault_b" "$BIN" sync push >/dev/null
git --git-dir="$remote" show main:vault.kv >"$vault_a"
chmod 600 "$vault_a"
recover_json="$(KIMEN_VAULT="$vault_a" "$BIN" sync pull --json)"
grep -q '"status":"unchanged"' <<<"$recover_json"
status_json="$(KIMEN_VAULT="$vault_a" "$BIN" sync status --json)"
grep -q '"status":"synced"' <<<"$status_json"

# Automatic mode pulls before use, pushes after mutations, and stays usable
# while the configured Git remote is temporarily unavailable.
KIMEN_VAULT="$vault_a" "$BIN" sync auto on >/dev/null
KIMEN_VAULT="$vault_b" "$BIN" sync auto on >/dev/null
printf 'automatic-b' | KIMEN_VAULT="$vault_b" "$BIN" secret set automatic_remote --stdin >/dev/null
test "$(KIMEN_VAULT="$vault_a" "$BIN" secret get automatic_remote --unsafe-stdout)" = "automatic-b"

offline_remote="$tmp/kimen-vault.offline"
mv "$remote" "$offline_remote"
printf 'saved-offline' | KIMEN_VAULT="$vault_a" "$BIN" secret set offline_secret --stdin 2>"$tmp/offline.err"
grep -q 'saved locally; remote is unavailable, so push is pending' "$tmp/offline.err"
test "$(KIMEN_VAULT="$vault_a" "$BIN" secret get offline_secret --unsafe-stdout --no-sync)" = "saved-offline"
mv "$offline_remote" "$remote"
KIMEN_VAULT="$vault_a" "$BIN" secret list >/dev/null
test "$(KIMEN_VAULT="$vault_b" "$BIN" secret get offline_secret --unsafe-stdout)" = "saved-offline"
auto_json="$(KIMEN_VAULT="$vault_a" "$BIN" sync auto status --json)"
grep -q '"enabled":true' <<<"$auto_json"

# Offline availability must not weaken real conflict detection once both sides
# have independently changed.
printf 'local-divergence' | KIMEN_VAULT="$vault_a" "$BIN" secret set diverged_local --stdin --no-sync >/dev/null
printf 'remote-divergence' | KIMEN_VAULT="$vault_b" "$BIN" secret set diverged_remote --stdin --no-sync >/dev/null
KIMEN_VAULT="$vault_b" "$BIN" sync push >/dev/null
printf 'must-not-be-written' >"$tmp/rejected.input"
expect_code 31 env KIMEN_VAULT="$vault_a" "$BIN" secret set rejected_write --stdin <"$tmp/rejected.input"
expect_code 1 env KIMEN_VAULT="$vault_a" "$BIN" secret get rejected_write --unsafe-stdout --no-sync

# A missing repository is a remote error; an existing branch without vault.kv
# is a clear precondition failure.
expect_code 30 env KIMEN_VAULT="$vault_a" "$BIN" sync status --remote "$tmp/does-not-exist.git"

empty_remote="$tmp/empty-vault.git"
seed="$tmp/seed"
git init --bare -q "$empty_remote"
git init -q "$seed"
git -C "$seed" -c user.name=Kimen -c user.email=kimen@localhost commit --allow-empty -q -m empty
git -C "$seed" push -q "$empty_remote" HEAD:refs/heads/main
expect_code 32 env KIMEN_VAULT="$tmp/new-machine/vault.kv" "$BIN" sync pull --remote "$empty_remote"

printf 'sync tests ok\n'
