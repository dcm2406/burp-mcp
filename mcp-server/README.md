# burp-mcp-server

Local Node.js ESM MCP server for the Burp MCP loopback bridge (Node >= 22.12, verified on 22 and 24). The executable
uses stdio: stdout is reserved for JSON-RPC and diagnostics go to stderr.

## Run

```powershell
npm ci
npm run build
burp-mcp
```

The active bridge is discovered from
`%LOCALAPPDATA%\burp-mcp\active-session.json`. For tests or an isolated install,
set `BURP_MCP_SESSION_FILE` to another descriptor path.

The descriptor is expected to contain `baseUrl`, `token`, and `sessionId`:

```json
{
  "schemaVersion": 1,
  "sessionId": "opaque-session-id",
  "baseUrl": "http://127.0.0.1:49152",
  "token": "opaque-bearer-token"
}
```

`baseUrl` is accepted only when it is literal loopback HTTP (`127.0.0.1`) with
an explicit port. Tool arguments cannot override the URL, token, or timeout.

## Development

```powershell
npm run build
npm test
npm run pack:check
```
