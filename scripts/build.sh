#!/usr/bin/env bash
# Linux/macOS twin of build.ps1: clean build, tests, fat JAR, MCP build/tests and npm pack.
# Native tools write diagnostics to stderr, which is fine on POSIX - "set -e" still
# catches every non-zero exit instead of relying on $LASTEXITCODE like Windows PowerShell.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Prefer the portable toolchain shipped in .tools/, fall back to the wrapper/PATH.
if [ -x "$ROOT/.tools/gradle-9.8.2/bin/gradle" ]; then
  GRADLE="$ROOT/.tools/gradle-9.8.2/bin/gradle"
else
  GRADLE="$ROOT/gradlew"
fi

if [ -d "$ROOT/.tools/node-v22.23.3-linux-x64/bin" ]; then
  export PATH="$ROOT/.tools/node-v22.23.3-linux-x64/bin:$PATH"
fi

cd "$ROOT"
"$GRADLE" --no-daemon clean test fatJar

cd "$ROOT/mcp-server"
npm ci
npm run build
npm test
npm pack

cd "$ROOT"
./scripts/ci-checks.sh
