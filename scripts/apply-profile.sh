#!/usr/bin/env bash
# Print the neutral MCP profile with ${BURP_MCP_ROOT} resolved to this repository.
# The output is ready to paste into an agent config; nothing is written for you.
#
# Usage: ./scripts/apply-profile.sh [--json|--jsonc]
set -euo pipefail

FORMAT="${1:---json}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

emit_json() {
  cat <<EOF
{
  "burp": {
    "command": ["node", "$ROOT/mcp-server/dist/cli.js"],
    "cwd": "$ROOT"
  }
}
EOF
}

emit_jsonc() {
  cat <<EOF
"burp": {
  "type": "local",
  "command": [
    "node",
    "$ROOT/mcp-server/dist/cli.js"
  ],
  "cwd": "$ROOT"
}
EOF
}

case "$FORMAT" in
  --json) emit_json ;;
  --jsonc) emit_jsonc ;;
  *) cat <<EOF
usage: $0 [--json|--jsonc]

  --json   Claude Code .mcp.json / Codex config.toml friendly block
  --jsonc  OpenCode opencode.json(c) mcp.servers block
EOF
     exit 2 ;;
esac
