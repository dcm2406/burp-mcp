# Burp MCP

Local [MCP](https://modelcontextprotocol.io) integration for Burp Suite Professional.

A Java Montoya extension runs a locked-down loopback HTTP bridge inside Burp; a TypeScript stdio
server exposes that bridge to MCP clients (OpenCode, Claude Code, Codex, …) as 8 tools.

> **Use only on systems you are authorized to test.** The bridge binds to literal `127.0.0.1` with a
> per-session bearer token, but it will send requests to any host you point it at — scope is your
> responsibility, not the bridge's.

## Features

| Tool | What it does |
|---|---|
| `burp_status` | Active session, versions, capabilities, limits |
| `burp_proxy_history` | Filter and cursor-paginate proxy history (SQLite metadata index keeps repeated queries cheap) |
| `burp_get_proxy_message` | Binary-safe raw request/response by opaque ref |
| `burp_send_http_request` | Send exactly one raw request through Burp |
| `burp_send_to_repeater` | Create a Repeater tab from a raw request without sending it |
| `burp_send_http_batch` | Send 1–50 requests in one call, bounded concurrency, per-item results |
| `burp_collaborator_payload` | Mint a Burp Collaborator payload for out-of-band detection |
| `burp_collaborator_interactions` | Poll Collaborator for DNS/HTTP/SMTP interactions (blind detection) |

Security properties: loopback-only bind, random port, 256-bit bearer token (constant-time compare),
session-bound HMAC refs, owner-only session descriptor, kill switch, unload cleanup, metadata-only
audit log, 10 MiB decoded payload cap per call.

## Install

Requires **Burp Suite Professional** and **Node.js >= 22.12**. A release install does **not** need
JDK, Gradle or a source checkout.

### Linux / macOS

Public repository:

```bash
curl -fsSL https://raw.githubusercontent.com/dcm2406/burp-mcp/main/scripts/install.sh | bash
```

Private repository (authenticate once with `gh auth login`):

```bash
gh api repos/dcm2406/burp-mcp/contents/scripts/install.sh \
  -H "Accept: application/vnd.github.raw" | bash
```

The installer downloads the latest release, verifies every downloaded file against `SHA256SUMS`,
installs under `~/.local/share/burp-mcp`, and registers the server with any detected OpenCode,
Claude Code or Codex CLI. Pin a release or keep your agent config untouched:

```bash
curl -fsSL https://raw.githubusercontent.com/dcm2406/burp-mcp/main/scripts/install.sh \
  | bash -s -- --version v0.1.0
curl -fsSL https://raw.githubusercontent.com/dcm2406/burp-mcp/main/scripts/install.sh \
  | bash -s -- --no-config
```

### Windows

```powershell
gh release download --repo dcm2406/burp-mcp --pattern install.ps1 --dir . --clobber
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

The default install directory is `%LOCALAPPDATA%\burp-mcp`. Pass `-Version v0.1.0` to pin a
release or `-NoConfig` to skip agent registration. Without `gh`, download `install.ps1` from the
release page.

### Finish

1. Burp → **Extensions → Installed → Add**, type **Java**, and select:
   - Linux/macOS: `~/.local/share/burp-mcp/extension/burp-mcp-extension.jar`
   - Windows: `%LOCALAPPDATA%\burp-mcp\extension\burp-mcp-extension.jar`
2. Open the **Burp MCP** tab and enable the bridge.
3. Restart your agent session, then confirm `opencode mcp list` (or `claude mcp list`) shows `burp`.

The installer keeps the extension JAR and MCP server from the same release, so re-running it
upgrades both together. See [`docs/installation.md`](docs/installation.md) for manual install,
offline use, options and uninstall.

## Build from source

Only needed for development. Requires JDK 21 and Node.js >= 22.12; no system-wide Gradle is
needed because the wrapper downloads its own distribution.

```bash
git clone https://github.com/dcm2406/burp-mcp.git
cd burp-mcp
./scripts/bootstrap.sh        # optional: fetch portable JDK/Node/Gradle into .tools/ (Windows: .\scripts\bootstrap.ps1)
./scripts/build.sh            # Windows: .\scripts\build.ps1
```

Artifacts:

```text
extension/build/libs/burp-mcp-extension-0.1.0-all.jar   # Burp extension (Jackson + SQLite bundled)
mcp-server/dist/                                        # MCP server (spawned by your agent)
```

Tests only:

```bash
./scripts/test.sh             # Windows: .\scripts\test.ps1
```

`dist/` is gitignored: release installs ship a prebuilt server, while contributors build it once.
The extension JAR and server must come from the same build; a stale server rejects a new bridge
with `BRIDGE_INVALID_RESPONSE`.

## Documentation

| Document | Contents |
|---|---|
| [`docs/installation.md`](docs/installation.md) | Release install, manual install, layout, upgrade, uninstall |
| [`docs/mcp-setup.md`](docs/mcp-setup.md) | Per-agent setup and troubleshooting |
| [`docs/architecture.md`](docs/architecture.md) | Bridge design, discovery, batch, Collaborator, history index |
| [`contracts/bridge-api.yaml`](contracts/bridge-api.yaml) | Normative bridge API contract |
| [`docs/threat-model.md`](docs/threat-model.md) | Assets, trust boundaries, accepted risks |
| [`docs/smoke-test.md`](docs/smoke-test.md) | End-to-end runbook (Windows + Linux) |
| [`docs/testing.md`](docs/testing.md) | Automated suite, packaging checks, manual gate |
| [`plans/`](plans/) | Historical MVP and design notes (Vietnamese) |

## Contributing and security

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the build/test workflow and
[`SECURITY.md`](SECURITY.md) for private vulnerability reporting.

## License

GPL-3.0 — see [LICENSE](LICENSE).
