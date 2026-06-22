#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${VERSION:-0.1.0-dev}"
VERSION="${VERSION#v}"
GOOS="${GOOS:-$(uname -s | tr '[:upper:]' '[:lower:]')}"
GOARCH="${GOARCH:-$(uname -m)}"
EXE_SUFFIX="${EXE_SUFFIX:-}"
ARCHIVE_FORMAT="${ARCHIVE_FORMAT:-tar.gz}"
KVIST_ROOT="${KVIST_ROOT:-$ROOT/../kvist}"
KVIST="${KVIST:-./kvist$EXE_SUFFIX}"

case "$GOARCH" in
  x86_64) GOARCH="amd64" ;;
  aarch64) GOARCH="arm64" ;;
esac

build_dir="$ROOT/tmp/release-build"
package_name="kimen_${VERSION}_${GOOS}_${GOARCH}"
package_dir="$ROOT/tmp/$package_name"
dist_dir="$ROOT/dist"
binary_name="kimen$EXE_SUFFIX"

rm -rf "$build_dir" "$package_dir"
mkdir -p "$build_dir" "$package_dir" "$dist_dir"

(
  cd "$KVIST_ROOT"
  "$KVIST" compile "$ROOT/src/main.kvist" -o "$build_dir/main.odin"
)

VERSION="$VERSION" perl -0pi -e 's/VERSION: string : "0\.1\.0-dev"/VERSION: string : "$ENV{VERSION}"/' "$build_dir/main.odin"
odin build "$build_dir" -out:"$build_dir/$binary_name"
"$build_dir/$binary_name" version | grep -qx "$VERSION"

cp "$build_dir/$binary_name" "$package_dir/$binary_name"

case "$ARCHIVE_FORMAT" in
  tar.gz)
    tar -C "$package_dir" -czf "$dist_dir/$package_name.tar.gz" "$binary_name"
    ;;
  zip)
    if command -v powershell.exe >/dev/null 2>&1; then
      powershell.exe -NoProfile -Command "Compress-Archive -Path '$package_dir/$binary_name' -DestinationPath '$dist_dir/$package_name.zip' -Force"
    else
      (cd "$package_dir" && zip -qr "$dist_dir/$package_name.zip" "$binary_name")
    fi
    ;;
  *)
    echo "unsupported archive format: $ARCHIVE_FORMAT" >&2
    exit 1
    ;;
esac
