#!/usr/bin/env bash
# Packaging invariants that a passing test suite does not prove. Used by CI and by
# scripts/build.sh so a local build catches the same regressions.
#
#   1. The fat JAR contains the application classes and the bundled dependencies.
#   2. The fat JAR must NOT contain the Montoya API (Burp provides it at runtime).
#   3. The npm tarball must contain dist/, package metadata and README only.
#
# Exits non-zero on the first violated invariant.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Locate artifacts without pipelines: a failing `ls`/glob under `set -e` aborts the script
# before the friendly error message can run.
first_match() {
  local candidate
  for candidate in "$@"; do
    [ -e "$candidate" ] || continue
    printf '%s\n' "$candidate"
    return 0
  done
  return 0
}

shopt -s nullglob
JAR_CANDIDATES=("$ROOT"/extension/build/libs/burp-mcp-extension-*-all.jar)
TARBALL_CANDIDATES=("$ROOT"/mcp-server/burp-mcp-server-*.tgz)
shopt -u nullglob

JAR="$(first_match "${JAR_CANDIDATES[@]}")"
TARBALL="$(first_match "${TARBALL_CANDIDATES[@]}")"

fail() { echo "ci-checks: $1" >&2; exit 1; }

[ -n "$JAR" ] || fail "fat JAR not found (run ./gradlew fatJar first)"
[ -n "$TARBALL" ] || fail "npm tarball not found (run npm pack in mcp-server first)"

listing() { unzip -l "$1" | awk '{print $4}'; }

# grep -c exits 1 when nothing matches; capture without letting pipefail abort.
count_pattern() {
  local matches
  matches="$(listing "$JAR" | grep -c "$1" || true)"
  echo "${matches:-0}"
}

[ "$(count_pattern 'io/github/burpmcp/.*\.class')" -gt 0 ] || fail "JAR missing io.github.burpmcp classes"
[ "$(count_pattern 'com/fasterxml/jackson')" -gt 0 ] || fail "JAR missing bundled Jackson"
[ "$(count_pattern 'org/sqlite')" -gt 0 ] || fail "JAR missing bundled SQLite driver"
[ "$(count_pattern 'burp/api/montoya')" -eq 0 ] || fail "JAR leaks the Montoya API (compileOnly boundary broken)"

entries="$(tar -tzf "$TARBALL")"
echo "$entries" | grep -q '^package/dist/cli.js$' || fail "tarball missing dist entrypoint"
echo "$entries" | grep -q '^package/package.json$' || fail "tarball missing package.json"
echo "$entries" | grep -q '^package/README.md$' || fail "tarball missing README.md"
if echo "$entries" | grep -qE '^package/(src|test|node_modules)/'; then
  fail "tarball contains source, tests or node_modules"
fi

echo "ci-checks: OK"
echo "  jar:     $(basename "$JAR") ($(du -h "$JAR" | cut -f1))"
echo "  tarball: $(basename "$TARBALL") ($(du -h "$TARBALL" | cut -f1))"
