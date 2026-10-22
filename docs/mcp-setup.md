# MCP client setup

How to register this project's MCP server with any agent. Everything here uses the same
stdio entrypoint — agents differ only in *where* the registration lives.

## Before you start

1. Build: `./scripts/build.sh` (creates `extension/build/libs/*.jar` and `mcp-server/dist/`).
2. Node >= 22.12 on your PATH (`node --version`).
3. Load the JAR in Burp and enable the bridge.
4. The server path in every example below is:

```text
<BURP_MCP_ROOT>/mcp-server/dist/cli.js
```

`<BURP_MCP_ROOT>` is the absolute path of your clone. Print it with:

```bash
./scripts/apply-profile.sh --jsonc     # OpenCode-style block
./scripts/apply-profile.sh --json      # Claude Code .mcp.json block
```

## OpenCode

### Project-local (recommended)

Create `opencode.jsonc` in the working directory where you use Burp tools:

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "burp": {
        "type": "local",
        "command": [
          "node",
          "/<BURP_MCP_ROOT>/mcp-server/dist/cli.js"
        ],
        "cwd": "/<BURP_MCP_ROOT>",
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

### Global

Put the same `mcp` block into `~/.config/opencode/opencode.json(c)` so every workspace has it.

### Desktop app on Windows

The desktop app manages its own configuration; register the server from its MCP settings UI
using the same command and arguments. Do not hand-edit anything else — the CLI config file is
shared between CLI and desktop on some builds and silently overwritten on others.

## Claude Code

```bash
claude mcp add burp -- node /<BURP_MCP_ROOT>/mcp-server/dist/cli.js
```

or add to `.mcp.json` (project) / `~/.claude.json` (user):

```json
{
  "mcpServers": {
    "burp": {
      "command": "node",
      "args": ["/<BURP_MCP_ROOT>/mcp-server/dist/cli.js"]
    }
  }
}
```

## Codex

`~/.codex/config.toml`:

```toml
[mcp_servers.burp]
command = "node"
args = ["/<BURP_MCP_ROOT>/mcp-server/dist/cli.js"]
```

## Any other agent (manual)

Anything that speaks MCP over stdio works. Point it at the command and argument below; no
environment variables are required, because the server reads the session descriptor itself.

```text
command: node <BURP_MCP_ROOT>/mcp-server/dist/cli.js
```

## Verify

```bash
opencode mcp list        # ✓ burp connected
```

Then call `burp_status` — it returns the Burp version, capabilities and limits, which also
proves descriptor discovery and authentication work.

## Keeping both sides in sync

The extension JAR and `mcp-server/dist/` must come from the **same build**. After changing
either one:

1. Rebuild (`./scripts/build.sh`).
2. Reload the extension in Burp.
3. Restart the agent session so it spawns a fresh server process.

Skipping step 3 produces `BRIDGE_INVALID_RESPONSE` — the running server is still the old build.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `BRIDGE_INVALID_RESPONSE` | Server predates the loaded JAR | Rebuild, reload extension, restart session |
| `DISCOVERY_NOT_FOUND` | Bridge disabled or Burp not running | Enable the bridge in the **Burp MCP** tab |
| `DISCOVERY_UNREADABLE` | Descriptor permissions | Rebuild/enable; the extension writes owner-only files |
| `STALE_REFERENCE` | Ref from an earlier session | Call `burp_proxy_history` for fresh refs |
| Spawn fails: `node not found` | Node missing or too old | Install Node >= 22.12 |
| Spawn fails: `Cannot find module dist/cli.js` | `dist/` not built | `./scripts/build.sh` |
| 401 `UNAUTHORIZED` | Caller not using the descriptor token | Do not hand-write `Authorization`; let the server do it |
