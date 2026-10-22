#!/usr/bin/env bash
# Fetch the portable toolchain into .tools/ (Linux/macOS twin of bootstrap.ps1).
#
# Keeps builds independent of system-wide installs and pins the exact versions this
# project is verified with. Idempotent: existing downloads are skipped.
#
#   Node    22.23.3   (project engines: >=22.12)
#   Gradle  9.8.2     (wrapper distribution, sha256-verified)
#
# JDK 21 is expected on the system (the Gradle toolchain resolves it automatically).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/.tools"
DOWNLOADS="$TOOLS/downloads"
NODE_VERSION="v22.23.3"
GRADLE_VERSION="9.8.2"
GRADLE_SHA256="21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"

mkdir -p "$DOWNLOADS"

log() { printf '[bootstrap] %s\n' "$1"; }

fetch() {
  local url="$1" target="$2"
  if [ -f "$target" ]; then
    log "exists: $(basename "$target")"
    return 0
  fi
  log "download: $url"
  curl -sSL --retry 3 -o "$target" "$url"
}

uname_s="$(uname -s)"
case "$uname_s" in
  Linux)  NODE_OS="linux-x64" ; NODE_EXT="tar.xz" ;;
  Darwin) NODE_OS="darwin-arm64" ; NODE_EXT="tar.gz" ;;
  *) echo "unsupported OS: $uname_s" >&2; exit 1 ;;
esac

NODE_ARCHIVE="$DOWNLOADS/node-${NODE_VERSION}-${NODE_OS}.${NODE_EXT}"
fetch "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-${NODE_OS}.${NODE_EXT}" "$NODE_ARCHIVE"
if [ ! -d "$TOOLS/node-${NODE_VERSION}-${NODE_OS}/bin" ]; then
  log "extract node"
  tar -xf "$NODE_ARCHIVE" -C "$TOOLS"
fi

GRADLE_ARCHIVE="$DOWNLOADS/gradle-${GRADLE_VERSION}-bin.zip"
fetch "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" "$GRADLE_ARCHIVE"
ACTUAL_SHA="$(sha256sum "$GRADLE_ARCHIVE" | cut -d' ' -f1)"
if [ "$ACTUAL_SHA" != "$GRADLE_SHA256" ]; then
  echo "gradle checksum mismatch: $ACTUAL_SHA (expected $GRADLE_SHA256)" >&2
  exit 1
fi
if [ ! -d "$TOOLS/gradle-${GRADLE_VERSION}/bin" ]; then
  log "extract gradle"
  unzip -q -o "$GRADLE_ARCHIVE" -d "$TOOLS"
fi

if command -v javac >/dev/null 2>&1; then
  log "javac: $(javac -version 2>&1)"
else
  log "WARNING: javac not found on PATH; the build needs JDK 21"
fi

log "done. Tools in $TOOLS:"
ls -1 "$TOOLS" | sed 's/^/  /'
