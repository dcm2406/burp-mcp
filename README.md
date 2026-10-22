# Burp MCP

Local [MCP](https://modelcontextprotocol.io) integration for Burp Suite Professional.

A Java Montoya extension runs a locked-down loopback HTTP bridge inside Burp; a TypeScript stdio
server exposes that bridge to MCP clients (OpenCode, Claude Code, Codex, …) as 7 tools.

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
| `burp_send_http_batch` | Send 1–50 requests in one call, bounded concurrency, per-item results |
| `burp_collaborator_payload` | Mint a Burp Collaborator payload for out-of-band detection |
| `burp_collaborator_interactions` | Poll Collaborator for DNS/HTTP/SMTP interactions (blind detection) |

Security properties: loopback-only bind, random port, 256-bit bearer token (constant-time compare),
session-bound HMAC refs, owner-only session descriptor, kill switch, unload cleanup, metadata-only
audit log, 10 MiB decoded payload cap per call.

## Requirements

- Burp Suite Professional (Montoya API 2026.7)
- JDK 21
- Node.js >= 22.12 (verified on 22 and 24)
- No system-wide Gradle needed — the wrapper downloads its own distribution

## Build

```bash
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
./scripts/test.sh              # Windows: .\scripts\test.ps1
```

## Install

1. Burp → **Extensions → Installed → Add**, type **Java**, select the fat JAR.
2. Open the **Burp MCP** tab and confirm the bridge is enabled on `127.0.0.1:<port>`.
3. Register the MCP server with your agent — see [`docs/mcp-setup.md`](docs/mcp-setup.md)
   (OpenCode, Claude Code, Codex, manual JSON) or use
   [`mcp-profile.jsonc`](mcp-profile.jsonc) as the neutral source.
4. Restart your agent session and confirm `opencode mcp list` (or the equivalent) shows `burp`.

> The extension JAR and `mcp-server/dist/` must come from the **same build**. A stale server
> rejects a new bridge with `BRIDGE_INVALID_RESPONSE` — rebuild both, reload the extension, and
> restart the agent session.

## Documentation

| Document | Contents |
|---|---|
| [`docs/mcp-setup.md`](docs/mcp-setup.md) | Per-agent setup, upgrade and troubleshooting |
| [`docs/architecture.md`](docs/architecture.md) | Bridge design, discovery, batch, Collaborator, history index |
| [`contracts/bridge-api.yaml`](contracts/bridge-api.yaml) | Normative bridge API contract |
| [`docs/threat-model.md`](docs/threat-model.md) | Assets, trust boundaries, accepted risks |
| [`docs/smoke-test.md`](docs/smoke-test.md) | End-to-end runbook (Windows + Linux) |
| [`plans/plan.md`](plans/plan.md) | MVP scope, verification evidence, known limitations |

## License

GPL-3.0 — see [LICENSE](LICENSE).
