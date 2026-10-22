# Architecture

## Components

```text
MCP client (OpenCode, Claude Code, Codex, …)
    │ MCP over stdio
    ▼
burp-mcp TypeScript process
    │ authenticated HTTP/JSON
    ▼
127.0.0.1:<ephemeral port>
    │
Burp MCP Java extension
    ├─ Proxy history
    ├─ Burp HTTP client
    ├─ Repeater
    └─ Swing control tab
```

The MCP process never connects directly to a target. Every active HTTP request is created and sent through the Montoya API inside Burp.

## Discovery and lifecycle

1. Enabling the extension creates a random `sessionId`, ephemeral port and 256-bit bearer token.
2. The extension atomically writes an owner-restricted session descriptor (`schemaVersion: 1`) under `%LOCALAPPDATA%\burp-mcp\active-session.json`, overridable with `BURP_MCP_SESSION_FILE`. On Linux/macOS, where `LOCALAPPDATA` does not exist, it falls back to `$XDG_DATA_HOME/burp-mcp/active-session.json` (default `~/.local/share/burp-mcp/active-session.json`); the MCP server resolves the identical path from segments on every platform.
3. OpenCode starts `burp-mcp` over stdio.
4. The MCP process reads the descriptor for each bridge call and validates the loopback URL. Tool arguments cannot override the URL, token or timeout.
5. Killing or unloading the extension closes the listener, invalidates the token and removes the descriptor.
6. Re-enabling creates a completely new session.

Each enable rotates the token and the HMAC key, so references and cursors from earlier sessions are rejected as stale.

Metadata-only audit records are appended to `burp-mcp\audit\bridge.jsonl` next to the session descriptor (on Windows `%LOCALAPPDATA%\burp-mcp\audit\`, on Linux/macOS `$XDG_DATA_HOME/burp-mcp/audit/` or `~/.local/share/burp-mcp/audit/`) (rotation: 1 MiB per file, 3 backups) and the last 100 events are mirrored into the Burp suite tab.

History references and pagination cursors are signed and session-bound. They intentionally become invalid after restart, re-enable or unload.

## Batch HTTP

`POST /v1/http/send-batch` sends 1..50 requests in one call, up to 10 in flight, each item sent
exactly once with no retry. The whole batch is validated before anything is sent (a malformed item
rejects the call with 400 and sends nothing); results keep item order and report `succeeded`/`failed`
counts, with a per-item `error` entry for individual send failures. Default `summary` mode returns
metadata only; `full` mode adds Base64 bodies under the same 10 MiB per-item limit as a single send
plus an 8 MiB budget across the batch (`omitted: true` per item beyond it). The audit log keeps one
line per batch call (`OK`/`PARTIAL`), not per item.

Both sides ship together: the extension applies `.strict()` schema validation on every response, so
loading a new JAR while an older MCP server process is still running fails tool calls with
`BRIDGE_INVALID_RESPONSE` instead of mis-parsing data. Reload the extension **and** restart the
OpenCode session (which re-spawns the MCP server from `dist/`) in the same step.

## Collaborator (out-of-band detection)

Two endpoints build blind-detection capability on Burp's own Collaborator engine
(`api.collaborator()`), so nothing but Burp talks to the Collaborator cloud:

- `POST /v1/collaborator/payload` mints a payload (optionally tagged with `customData`) and
  returns an opaque session-bound `ref` plus the raw payload string. At most 20 per session.
- `POST /v1/collaborator/interactions` polls by `ref`, by raw payload string, or for the whole
  session. Default mode `full` returns DNS/HTTP/SMTP detail including Base64 bodies of the hit,
  because the detail is the evidence (e.g. a DNS query carrying exfiltrated data); `summary`
  returns metadata only. Burp hands each interaction over exactly once, so every poll returns only
  what arrived since the previous poll and `newCount` always equals `total`.

Session-bound by design: a new bridge session gets a fresh Collaborator client, so payloads from
an older session cannot be polled after re-enable (no `restoreClient`, no secret on disk). Audit
records one metadata-only line per call. Because Burp must reach the Collaborator cloud, verify
the outbound channel (`dig +short <payload-domain>` from the same machine) before trusting an
empty result - "no interaction" only means something once the channel is proven to work.

## Proxy history index

Montoya exposes no metadata-only history API: `proxy().history()` materialises every request and
response, and `history(ProxyHistoryFilter)` only moves the predicate into Java. Query and single-message
fetch therefore repeat that full materialisation.

To amortise it, each session keeps a local SQLite **metadata index** at
`<data>/burp-mcp/index/history-<sessionId>.db` (same directory rules as the descriptor: XDG on
Linux/macOS, `%LOCALAPPDATA%` on Windows, owner-only permissions, deleted on disable/unload):

- Holds metadata only (id, time, method, host/port/secure, status, mime, edited, listenerPort,
  request/response byte lengths). Raw request/response bytes never touch disk.
- Refreshed at most once per TTL window (default 15s, `burp.mcp.index.ttl.ms`); each refresh upserts
  new ids and deletes ids Burp no longer reports.
- `query` runs against SQLite when the `search` filter is absent; an empty result forces one fresh
  scan first, so deletions in Burp never look like "no matches".
- `search` matches raw bytes, so it always uses the live Burp scan and bypasses the index.
- Any index failure falls back to the live scan: the index is a cache, never a source of truth.
- `burp_get_proxy_message` still fetches bytes from Burp (there is no get-by-id API); the index only
  makes listing and filtering cheap.

Known limitation: Montoya has no proxy-history-added event (its response handlers fire only during
interception), so freshness is bounded by the TTL window rather than being push-based.

## Payload model

HTTP messages are binary and therefore cross the JSON bridge as Base64. The decoded raw budget is 10 MiB per tool input and, separately, 10 MiB for the combined raw output of one call (echoed request plus response); output beyond that budget is truncated and reported with `byteLength`, `returnedByteLength`, `truncated` and `sha256`. The JSON envelope limit is 14 MiB.

## Concurrency

The bridge uses a bounded worker pool (2–4 threads, queue of 16) and rejects overflow with `503 QUEUE_FULL`. HTTP exchange handling runs on the extension's own threads, never on Swing's Event Dispatch Thread, and each exchange is closed only after its response has been written. Side-effecting MCP calls are never retried automatically.
