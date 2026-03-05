#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/release-calver.sh vYYYY.M.PATCH [--push]

Examples:
  scripts/release-calver.sh v2026.2.1
  scripts/release-calver.sh v2026.2.1 --push

Behavior:
  - validates CalVer tag format: vYYYY.M.PATCH
  - runs `bb test-all`, `bb e2e-sync-all`, and `bb build-jar`
  - creates an annotated git tag
  - optionally pushes the tag with --push
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" || "${1:-}" == "" ]]; then
  usage
  exit 0
fi

TAG="$1"
PUSH=false
if [[ "${2:-}" == "--push" ]]; then
  PUSH=true
elif [[ "${2:-}" != "" ]]; then
  echo "unknown argument: $2" >&2
  usage >&2
  exit 1
fi

if [[ ! "$TAG" =~ ^v[0-9]{4}\.[0-9]+\.[0-9]+$ ]]; then
  echo "invalid CalVer tag: $TAG (expected vYYYY.M.PATCH)" >&2
  exit 1
fi

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "tag already exists: $TAG" >&2
  exit 1
fi

echo "Running release checks..."
bb test-all
bb e2e-sync-all
bb build-jar

echo "Creating tag $TAG"
git tag -a "$TAG" -m "Release $TAG"

if [[ "$PUSH" == "true" ]]; then
  echo "Pushing tag $TAG"
  git push origin "$TAG"
else
  echo "Tag created locally. To publish release, run:"
  echo "  git push origin $TAG"
fi
