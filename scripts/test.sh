#!/usr/bin/env bash
# Linux/macOS twin of test.ps1: Java tests plus the full TypeScript suite.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -x "$ROOT/.tools/gradle-9.8.2/bin/gradle" ]; then
  GRADLE="$ROOT/.tools/gradle-9.8.2/bin/gradle"
else
  GRADLE="$ROOT/gradlew"
fi

if [ -d "$ROOT/.tools/node-v22.23.3-linux-x64/bin" ]; then
  export PATH="$ROOT/.tools/node-v22.23.3-linux-x64/bin:$PATH"
fi

cd "$ROOT"
"$GRADLE" --no-daemon test

cd "$ROOT/mcp-server"
npm ci
npm test
