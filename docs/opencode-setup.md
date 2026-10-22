# OpenCode V2 setup

Build the project and load the JAR into Burp before connecting OpenCode.

## Project-local config using portable Node

Create `opencode.jsonc` in the project where the Burp tools should be available:

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "burp": {
        "type": "local",
        "command": [
          "D:\\mbos\\burp-mcp\\.tools\\node-v22.23.3-win-x64\\node.exe",
          "D:\\mbos\\burp-mcp\\mcp-server\\dist\\cli.js"
        ],
        "cwd": "D:\\mbos\\burp-mcp",
        "codemode": true,
        "timeout": {
          "startup": 30000,
          "catalog": 30000,
          "execution": 600000
        }
      }
    }
  }
}
```

The entrypoint is `dist/cli.js`. `dist/index.js` only re-exports the library and does not
start the stdio transport.

The `protocol` field is deliberately omitted. It accepts `legacy` (default), `auto`, or
`2026-07-28`, but `@modelcontextprotocol/server` 2.3.1 supports up to `2025-11-25`, so the
classic handshake that OpenCode uses by default is the working choice.

If the descriptor is stored elsewhere, add:

```jsonc
"environment": {
  "BURP_MCP_SESSION_FILE": "D:\\secure\\burp-session.json"
}
```

Do not put the bearer token in OpenCode configuration. The MCP server reads the short-lived
descriptor directly.

## Kali / Linux config

Same config with POSIX paths. No `BURP_MCP_SESSION_FILE` override is needed: both sides resolve
the descriptor to `~/.local/share/burp-mcp/active-session.json` (respecting `XDG_DATA_HOME` if set),
and the extension enforces owner-only `0600` permissions there.

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "burp": {
        "type": "local",
        "command": [
          "/home/kali/burp-mcp/.tools/node-v22.23.3-linux-x64/bin/node",
          "/home/kali/burp-mcp/mcp-server/dist/cli.js"
        ],
        "cwd": "/home/kali/burp-mcp",
        "codemode": true,
        "timeout": {
          "startup": 30000,
          "catalog": 30000,
          "execution": 600000
        }
      }
    }
  }
}
```

The config points at the portable Node 22.23.3 build in `.tools` for reproducibility, but the
system Node 24 on Kali also works: `engines` is `>=22.12.0` and the server has been verified on
both 22 and 24 (handshake, all 8 tools, clean JSON-RPC on stdout). If you prefer the system
Node, point `command` at `/usr/bin/node` instead.
Verified live against Burp Suite Professional on Kali: `burp_status`, `burp_proxy_history`,
`burp_get_proxy_message` and tampered-ref rejection all behave per `contracts/bridge-api.yaml`.

## Verify

```text
opencode mcp list
```

Expected state:

```text
✓ burp  connected
```

Tool names are grouped under server `burp` in the default Code Mode. When both Burp and
OpenCode run on Windows, prefer the global config (`~/.config/opencode/opencode.json`) so the
server is available in every workspace instead of re-declaring it per project.
