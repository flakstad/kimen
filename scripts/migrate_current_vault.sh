#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SOURCE_BIN="$REPO_ROOT/dist/kimen-go"
TARGET_BIN="$REPO_ROOT/bin/kimen"
SOURCE_VAULT=""
TARGET_VAULT=""
SOURCE_PASS_FILE=""
TARGET_PASS_FILE=""
SOURCE_PASS_CMD=""
TARGET_PASS_CMD=""
BUILD_GO=1
MAKE_BACKUP=1
SWITCH_DEFAULT=1
ASSUME_YES=0

TMP_FILES=()
cleanup() {
  local f
  for f in "${TMP_FILES[@]:-}"; do
    if [[ -n "$f" && -e "$f" ]]; then
      rm -f "$f"
    fi
  done
  return 0
}
trap cleanup EXIT

usage() {
  cat <<'USAGE'
Usage: scripts/migrate_current_vault.sh [options]

Migrates secrets from a Go-era Kimen vault into the Clojure vault implementation.

Options:
  --source-vault <path>       Source Go-era vault path
  --target-vault <path>       Target Clojure vault path (default: source with .clj.db suffix)
  --source-bin <path>         Go-era kimen binary (default: ./dist/kimen-go)
  --target-bin <path>         Clojure kimen launcher (default: ./bin/kimen)
  --source-pass-file <path>   File containing source vault passphrase
  --target-pass-file <path>   File containing target vault passphrase
  --source-pass-cmd <cmd>     Command used for source passphrase
  --target-pass-cmd <cmd>     Command used for target passphrase
  --no-build-go               Skip building Go binary
  --skip-backup               Skip source vault backup
  --no-switch                 Do not set migrated vault as default config vault
  --yes                       Skip confirmation prompt after dry-run
  --help                      Show this help

Notes:
  - If source/target passphrase flags are omitted, prompts are shown.
  - The script always runs a dry-run before real migration.
USAGE
}

die() {
  echo "error: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

json_get_path() {
  bb -e '(require (quote [kimen.json :as json])) (println (get (json/read-str (slurp *in*)) "path"))'
}

json_count_names() {
  bb -e '(require (quote [kimen.json :as json])) (println (count (or (get (json/read-str (slurp *in*)) "names") [])))'
}

derive_default_target_vault() {
  local src="$1"
  if [[ "$src" == *.db ]]; then
    printf '%s' "${src%.db}.clj.db"
  else
    printf '%s' "${src}.clj"
  fi
}

make_tmp_secret_file_from_prompt() {
  local prompt="$1"
  local tmp
  tmp="$(mktemp /tmp/kimen-pass.XXXXXX)"
  chmod 600 "$tmp"
  local pass
  read -rsp "$prompt" pass
  echo
  [[ -n "$pass" ]] || die "empty passphrase not allowed"
  printf '%s' "$pass" > "$tmp"
  unset pass
  TMP_FILES+=("$tmp")
  printf '%s' "$tmp"
}

make_passphrase_cmd_from_file() {
  local pass_file="$1"
  [[ -f "$pass_file" ]] || die "passphrase file not found: $pass_file"
  local wrapper
  wrapper="$(mktemp /tmp/kimen-passcmd.XXXXXX)"
  chmod 700 "$wrapper"
  local escaped
  escaped="$(printf '%q' "$pass_file")"
  cat > "$wrapper" <<CMD
#!/usr/bin/env bash
set -euo pipefail
cat $escaped
CMD
  TMP_FILES+=("$wrapper")
  printf '%s' "$wrapper"
}

while (($#)); do
  case "$1" in
    --source-vault)
      SOURCE_VAULT="${2:-}"
      shift 2
      ;;
    --target-vault)
      TARGET_VAULT="${2:-}"
      shift 2
      ;;
    --source-bin)
      SOURCE_BIN="${2:-}"
      shift 2
      ;;
    --target-bin)
      TARGET_BIN="${2:-}"
      shift 2
      ;;
    --source-pass-file)
      SOURCE_PASS_FILE="${2:-}"
      shift 2
      ;;
    --target-pass-file)
      TARGET_PASS_FILE="${2:-}"
      shift 2
      ;;
    --source-pass-cmd)
      SOURCE_PASS_CMD="${2:-}"
      shift 2
      ;;
    --target-pass-cmd)
      TARGET_PASS_CMD="${2:-}"
      shift 2
      ;;
    --no-build-go)
      BUILD_GO=0
      shift
      ;;
    --skip-backup)
      MAKE_BACKUP=0
      shift
      ;;
    --no-switch)
      SWITCH_DEFAULT=0
      shift
      ;;
    --yes)
      ASSUME_YES=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1"
      ;;
  esac
done

need_cmd bb
[[ -x "$TARGET_BIN" ]] || die "target bin not executable: $TARGET_BIN"

cd "$REPO_ROOT"

if [[ -z "$SOURCE_VAULT" ]]; then
  SOURCE_VAULT="$("$TARGET_BIN" vault path --json | json_get_path)"
fi
[[ -n "$SOURCE_VAULT" ]] || die "could not resolve source vault path"

if [[ -z "$TARGET_VAULT" ]]; then
  TARGET_VAULT="$(derive_default_target_vault "$SOURCE_VAULT")"
fi

[[ "$SOURCE_VAULT" != "$TARGET_VAULT" ]] || die "source and target vault paths must differ"
[[ -f "$SOURCE_VAULT" ]] || die "source vault not found: $SOURCE_VAULT"
[[ ! -e "$TARGET_VAULT" ]] || die "target vault already exists: $TARGET_VAULT"

if (( BUILD_GO )); then
  need_cmd go
  mkdir -p "$(dirname "$SOURCE_BIN")"
  echo "Building Go source binary: $SOURCE_BIN"
  go build -o "$SOURCE_BIN" ./cmd/kimen
fi
[[ -x "$SOURCE_BIN" ]] || die "source bin not executable: $SOURCE_BIN"

if (( MAKE_BACKUP )); then
  BACKUP_PATH="${SOURCE_VAULT}.bak.$(date +%Y%m%d-%H%M%S)"
  cp "$SOURCE_VAULT" "$BACKUP_PATH"
  echo "Created source vault backup: $BACKUP_PATH"
fi

if [[ -n "$SOURCE_PASS_CMD" && -n "$SOURCE_PASS_FILE" ]]; then
  die "use only one of --source-pass-cmd or --source-pass-file"
fi
if [[ -z "$SOURCE_PASS_CMD" ]]; then
  if [[ -z "$SOURCE_PASS_FILE" ]]; then
    SOURCE_PASS_FILE="$(make_tmp_secret_file_from_prompt "Source vault passphrase: ")"
  fi
  SOURCE_PASS_CMD="$(make_passphrase_cmd_from_file "$SOURCE_PASS_FILE")"
fi

if [[ -n "$TARGET_PASS_CMD" && -n "$TARGET_PASS_FILE" ]]; then
  die "use only one of --target-pass-cmd or --target-pass-file"
fi
if [[ -z "$TARGET_PASS_CMD" ]]; then
  if [[ -z "$TARGET_PASS_FILE" ]]; then
    TARGET_PASS_FILE="$(make_tmp_secret_file_from_prompt "Target vault passphrase: ")"
  fi
  TARGET_PASS_CMD="$(make_passphrase_cmd_from_file "$TARGET_PASS_FILE")"
fi

MIGRATE_BASE_ARGS=(
  migrate-go-vault
  --
  --source-bin "$SOURCE_BIN"
  --source-vault "$SOURCE_VAULT"
  --source-passphrase-cmd "$SOURCE_PASS_CMD"
  --target-bin "$TARGET_BIN"
  --target-vault "$TARGET_VAULT"
  --target-passphrase-cmd "$TARGET_PASS_CMD"
  --init-target
)

echo "Running migration dry-run..."
bb "${MIGRATE_BASE_ARGS[@]}" --dry-run --json

if (( ! ASSUME_YES )); then
  read -r -p "Continue with real migration? [y/N] " CONFIRM
  case "${CONFIRM:-}" in
    y|Y|yes|YES)
      ;;
    *)
      echo "Aborted after dry-run."
      exit 0
      ;;
  esac
fi

echo "Running real migration..."
bb "${MIGRATE_BASE_ARGS[@]}" --json

SRC_COUNT="$("$SOURCE_BIN" secret list --vault "$SOURCE_VAULT" --passphrase-cmd "$SOURCE_PASS_CMD" --json | json_count_names)"
DST_COUNT="$("$TARGET_BIN" secret list --vault "$TARGET_VAULT" --passphrase-cmd "$TARGET_PASS_CMD" --json | json_count_names)"

echo "source_secret_count=$SRC_COUNT"
echo "target_secret_count=$DST_COUNT"

if [[ "$SRC_COUNT" != "$DST_COUNT" ]]; then
  echo "warning: source and target secret counts differ" >&2
fi

if (( SWITCH_DEFAULT )); then
  echo "Switching default configured vault to: $TARGET_VAULT"
  "$TARGET_BIN" config vault set "$TARGET_VAULT" --json >/dev/null
fi

echo "Target vault metadata:"
"$TARGET_BIN" vault info --vault "$TARGET_VAULT" --json

echo "Migration script complete."
