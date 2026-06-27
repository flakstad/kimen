#!/usr/bin/env bash
# Copyright (c) Andreas Flakstad and Kimen contributors
# SPDX-License-Identifier: MIT

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
ODIN_TARGET="${ODIN_TARGET:-}"

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
odin_args=(build "$build_dir" "-out:$build_dir/$binary_name")
if [[ -n "$ODIN_TARGET" ]]; then
  odin_args+=("-target:$ODIN_TARGET")
fi
odin "${odin_args[@]}"
if [[ -z "$ODIN_TARGET" ]]; then
  "$build_dir/$binary_name" version | grep -qx "$VERSION"
fi

cp "$build_dir/$binary_name" "$package_dir/$binary_name"

case "$ARCHIVE_FORMAT" in
  tar.gz)
    tar -C "$package_dir" -czf "$dist_dir/$package_name.tar.gz" "$binary_name"
    ;;
  zip)
    if command -v powershell.exe >/dev/null 2>&1; then
      src_path="$package_dir/$binary_name"
      dst_path="$dist_dir/$package_name.zip"
      if command -v cygpath >/dev/null 2>&1; then
        src_path="$(cygpath -w "$src_path")"
        dst_path="$(cygpath -w "$dst_path")"
      fi
      powershell.exe -NoProfile -Command "Compress-Archive -LiteralPath '$src_path' -DestinationPath '$dst_path' -Force"
    else
      (cd "$package_dir" && zip -qr "$dist_dir/$package_name.zip" "$binary_name")
    fi
    ;;
  *)
    echo "unsupported archive format: $ARCHIVE_FORMAT" >&2
    exit 1
    ;;
esac
