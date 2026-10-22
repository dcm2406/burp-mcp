#!/usr/bin/env bash
# Install a released burp-mcp build (Burp extension JAR + stdio MCP server) and
# register it with detected MCP clients. No clone or build required.
#
# Usage:
#   ./install.sh                     # latest release
#   ./install.sh --version v0.1.0
#   ./install.sh --local             # use this checkout's build output
#   ./install.sh --no-config         # install files only
#   ./install.sh --dry-run
#   ./install.sh --uninstall
set -euo pipefail

REPO="${BURP_MCP_REPO:-dcm2406/burp-mcp}"
DEFAULT_PREFIX="${XDG_DATA_HOME:-$HOME/.local/share}/burp-mcp"
PREFIX="$DEFAULT_PREFIX"
PREFIX_EXPLICIT=0
VERSION=""
TAG=""
LOCAL=0
NO_CONFIG=0
DRY_RUN=0
UNINSTALL=0
TMP=""

say()  { printf '%s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die()  { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Install burp-mcp from a GitHub release.

Options:
  --version vX.Y.Z   Release tag to install (default: latest)
  --prefix DIR       Install directory (default: ~/.local/share/burp-mcp)
  --local            Use extension/build/libs and mcp-server/ from this checkout
  --no-config        Do not modify OpenCode/Claude Code/Codex configuration
  --dry-run          Print what would happen, change nothing
  --uninstall        Remove installed files and the Claude Code entry
  -h, --help         Show this help

Environment:
  BURP_MCP_REPO      GitHub repository (default: dcm2406/burp-mcp)
  XDG_DATA_HOME      Base for the default install directory
EOF
}

cleanup() {
  if [ -n "$TMP" ] && [ -d "$TMP" ]; then
    rm -rf "$TMP"
  fi
}
trap cleanup EXIT

while [ $# -gt 0 ]; do
  case "$1" in
    --version)
      [ $# -ge 2 ] || die "--version needs a value"
      VERSION="$2"
      shift 2
      ;;
    --prefix)
      [ $# -ge 2 ] || die "--prefix needs a value"
      PREFIX="$2"
      PREFIX_EXPLICIT=1
      shift 2
      ;;
    --local)     LOCAL=1; shift ;;
    --no-config) NO_CONFIG=1; shift ;;
    --dry-run)   DRY_RUN=1; shift ;;
    --uninstall) UNINSTALL=1; shift ;;
    -h|--help)   usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

SCRIPT_SOURCE="${BASH_SOURCE[0]:-}"
SCRIPT_DIR=""
if [ -n "$SCRIPT_SOURCE" ]; then
  SCRIPT_DIR="$(cd "$(dirname "$SCRIPT_SOURCE")" && pwd)"
fi

# A copy stored at PREFIX/install.sh should upgrade/uninstall that prefix by default.
if [ "$PREFIX_EXPLICIT" -eq 0 ] && [ -n "$SCRIPT_DIR" ] && { [ -d "$SCRIPT_DIR/server" ] || [ -d "$SCRIPT_DIR/extension" ]; }; then
  PREFIX="$SCRIPT_DIR"
fi

case "$PREFIX" in
  ""|"/"|"$HOME"|"$HOME/") die "refusing to use $PREFIX as the install directory" ;;
esac

if [ "$UNINSTALL" -eq 1 ]; then
  if [ ! -d "$PREFIX" ]; then
    say "nothing to remove at $PREFIX"
    exit 0
  fi
  if [ ! -d "$PREFIX/server" ] && [ ! -d "$PREFIX/extension" ]; then
    die "refusing to remove $PREFIX: no burp-mcp installation found"
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    say "would remove the burp-mcp files from $PREFIX"
    exit 0
  fi
  rm -rf "$PREFIX/server" "$PREFIX/extension" "$PREFIX/bin"
  rm -f "$PREFIX/install.sh" "$PREFIX/install.ps1"
  if rmdir "$PREFIX" 2>/dev/null; then
    say "removed $PREFIX"
  else
    say "removed the burp-mcp files from $PREFIX"
    say "left other files there (session descriptor, audit log, index)"
  fi
  if command -v claude >/dev/null 2>&1; then
    details="$(claude mcp get burp 2>/dev/null || true)"
    if [ -n "$details" ] && grep -Fq -- "$PREFIX" <<<"$details"; then
      claude mcp remove --scope user burp >/dev/null 2>&1 || true
      say "removed the Claude Code user entry for burp"
    elif [ -n "$details" ]; then
      say "left the Claude Code entry for burp alone (it does not point to $PREFIX)"
    fi
  fi
  say "Remove the OpenCode/Codex entry manually if it is still present."
  exit 0
fi

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "no SHA-256 tool found (install coreutils or use macOS shasum)"
  fi
}

require_node() {
  command -v node >/dev/null 2>&1 || die "Node.js >= 22.12 is required: https://nodejs.org"
  local version major minor
  version="$(node -p 'process.versions.node')"
  major="${version%%.*}"
  minor="${version#*.}"
  minor="${minor%%.*}"
  if [ "$major" -lt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -lt 12 ]; }; then
    die "Node.js >= 22.12 is required (found $version)"
  fi
  NODE_BIN="$(command -v node)"
}

resolve_version() {
  if [ -n "$VERSION" ]; then
    TAG="$VERSION"
    case "$TAG" in v*) ;; *) TAG="v$TAG" ;; esac
    VERSION="${TAG#v}"
    return
  fi
  if command -v gh >/dev/null 2>&1; then
    TAG="$(gh api "repos/$REPO/releases/latest" --jq .tag_name 2>/dev/null || true)"
  fi
  if [ -z "$TAG" ]; then
    command -v curl >/dev/null 2>&1 || die "GitHub CLI (gh) or curl is required to resolve the latest release; pass --version"
    TAG="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
      | node -e 'let body="";process.stdin.on("data",c=>body+=c);process.stdin.on("end",()=>{try{process.stdout.write(JSON.parse(body).tag_name||"")}catch{}})')" || true
  fi
  [ -n "$TAG" ] || die "cannot resolve the latest release for $REPO (run 'gh auth login', pass --version, or check the repository name)"
  VERSION="${TAG#v}"
}

download_asset() {
  local name="$1"
  if command -v gh >/dev/null 2>&1; then
    if gh release download "$TAG" --repo "$REPO" --dir "$TMP" --clobber --pattern "$name" >/dev/null 2>&1; then
      return 0
    fi
  fi
  command -v curl >/dev/null 2>&1 || die "curl is required when gh is unavailable"
  # Public-repository fallback. Private repositories require gh.
  curl -fsSL "https://github.com/$REPO/releases/download/$TAG/$name" -o "$TMP/$name" \
    || die "cannot download $name from $REPO@$TAG (for a private repo run 'gh auth login')"
}

require_node

if [ -n "$SCRIPT_DIR" ]; then
  ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
else
  ROOT="$(pwd)"
fi

if [ "$LOCAL" -eq 1 ]; then
  [ -f "$ROOT/mcp-server/dist/cli.js" ] || die "local build missing: run ./scripts/build.sh first"
  [ -d "$ROOT/mcp-server/node_modules" ] || die "local node_modules missing: run ./scripts/build.sh first"
  JAR=""
  for candidate in "$ROOT"/extension/build/libs/*-all.jar; do
    if [ -f "$candidate" ]; then JAR="$candidate"; break; fi
  done
  [ -n "$JAR" ] || die "local JAR missing: run ./scripts/build.sh first"
  VERSION="$(node -e 'console.log(require(process.argv[1]).version)' "$ROOT/mcp-server/package.json")"
  TAG="local"
else
  resolve_version
fi

if [ "$DRY_RUN" -eq 1 ]; then
  say "would install burp-mcp $VERSION"
  say "  source: $TAG"
  say "  prefix: $PREFIX"
  say "  node:   $NODE_BIN"
  say "  server: $PREFIX/server/dist/cli.js"
  say "  jar:    $PREFIX/extension/burp-mcp-extension.jar"
  exit 0
fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/burp-mcp-install.XXXXXX")"

if [ "$LOCAL" -eq 1 ]; then
  mkdir -p "$TMP/extract/server"
  cp -R "$ROOT/mcp-server/dist" "$TMP/extract/server/dist"
  cp "$ROOT/mcp-server/package.json" "$TMP/extract/server/package.json"
  if [ -f "$ROOT/mcp-server/README.md" ]; then
    cp "$ROOT/mcp-server/README.md" "$TMP/extract/server/README.md"
  fi
  if [ -f "$ROOT/LICENSE" ]; then
    cp "$ROOT/LICENSE" "$TMP/extract/server/LICENSE"
  fi
  cp -R "$ROOT/mcp-server/node_modules" "$TMP/extract/server/node_modules"
  rm -rf "$TMP/extract/server/node_modules/.bin"
  cp "$JAR" "$TMP/burp-mcp-extension-$VERSION.jar"
else
  command -v tar >/dev/null 2>&1 || die "tar is required"
  download_asset "burp-mcp-extension-$VERSION.jar"
  download_asset "burp-mcp-server-$VERSION.tar.gz"
  download_asset "install.sh"
  download_asset "SHA256SUMS"

  verified=0
  while read -r expected name; do
    [ -n "${expected:-}" ] || continue
    name="${name#\*}"
    [ -n "$name" ] || continue
    [ -f "$TMP/$name" ] || continue
    actual="$(sha256_file "$TMP/$name")"
    [ "$actual" = "$expected" ] || die "checksum mismatch for $name"
    verified=$((verified + 1))
  done < "$TMP/SHA256SUMS"
  [ "$verified" -ge 3 ] || die "SHA256SUMS is missing entries for the release assets"

  mkdir -p "$TMP/extract"
  tar -xzf "$TMP/burp-mcp-server-$VERSION.tar.gz" -C "$TMP/extract"
  [ -f "$TMP/extract/server/dist/cli.js" ] || die "server bundle is malformed (missing dist/cli.js)"
  JAR=""
  for candidate in "$TMP"/burp-mcp-extension-*.jar; do
    if [ -f "$candidate" ]; then JAR="$candidate"; break; fi
  done
  [ -n "$JAR" ] || die "release is missing the extension JAR"
fi

mkdir -p "$PREFIX"
PREFIX="$(cd "$PREFIX" && pwd)"
SERVER_DIR="$PREFIX/server"
EXT_DIR="$PREFIX/extension"
BIN_DIR="$PREFIX/bin"
CLI="$SERVER_DIR/dist/cli.js"

rm -rf "$SERVER_DIR" "$EXT_DIR"
mkdir -p "$SERVER_DIR" "$EXT_DIR" "$BIN_DIR"

cp -R "$TMP/extract/server/." "$SERVER_DIR/"
printf '%s\n' "$VERSION" > "$SERVER_DIR/VERSION"
cp "$JAR" "$EXT_DIR/burp-mcp-extension-$VERSION.jar"
cp "$JAR" "$EXT_DIR/burp-mcp-extension.jar"

if [ -f "$TMP/install.sh" ]; then
  cp "$TMP/install.sh" "$PREFIX/install.sh"
elif [ -f "$ROOT/scripts/install.sh" ]; then
  cp "$ROOT/scripts/install.sh" "$PREFIX/install.sh"
fi
if [ -f "$PREFIX/install.sh" ]; then
  chmod +x "$PREFIX/install.sh"
fi

cat > "$BIN_DIR/burp-mcp" <<EOF
#!/usr/bin/env sh
exec "$NODE_BIN" "$CLI" "\$@"
EOF
chmod +x "$BIN_DIR/burp-mcp"

CONFIG_NOTE=""
if [ "$NO_CONFIG" -eq 0 ]; then
  if command -v opencode >/dev/null 2>&1; then
    if opencode mcp add --global burp -- "$NODE_BIN" "$CLI" >/dev/null 2>&1; then
      CONFIG_NOTE="${CONFIG_NOTE:+$CONFIG_NOTE, }OpenCode (global)"
    else
      warn "could not update the OpenCode config automatically"
    fi
  fi
  if command -v claude >/dev/null 2>&1; then
    claude mcp remove --scope user burp >/dev/null 2>&1 || true
    if claude mcp add --scope user burp -- "$NODE_BIN" "$CLI" >/dev/null 2>&1; then
      CONFIG_NOTE="${CONFIG_NOTE:+$CONFIG_NOTE, }Claude Code (user)"
    else
      warn "could not update the Claude Code config automatically"
    fi
  fi
  if command -v codex >/dev/null 2>&1 && codex mcp add --help >/dev/null 2>&1; then
    if codex mcp add burp -- "$NODE_BIN" "$CLI" >/dev/null 2>&1; then
      CONFIG_NOTE="${CONFIG_NOTE:+$CONFIG_NOTE, }Codex"
    else
      warn "could not update the Codex config automatically"
    fi
  fi
fi

say ""
say "burp-mcp $VERSION installed"
say "  server : $CLI"
say "  jar    : $EXT_DIR/burp-mcp-extension.jar"
if [ -n "$CONFIG_NOTE" ]; then
  say "  config : $CONFIG_NOTE"
fi
say ""
say "Next:"
say "  1. Burp -> Extensions -> Installed -> Add -> Java"
say "     select $EXT_DIR/burp-mcp-extension.jar"
say "  2. Open the Burp MCP tab and enable the bridge."
say "  3. Restart your agent session so it spawns the new server."

if [ "$NO_CONFIG" -eq 1 ] || [ -z "$CONFIG_NOTE" ]; then
  cat <<EOF

Manual registration:
  command: $NODE_BIN
  args:    ["$CLI"]

  Claude Code: claude mcp add --scope user burp -- "$NODE_BIN" "$CLI"
  OpenCode:    opencode mcp add --global burp -- "$NODE_BIN" "$CLI"
  Codex:       codex mcp add burp -- "$NODE_BIN" "$CLI"
EOF
fi
