# Installation

`burp-mcp` ships as two artifacts that must come from the **same release**:

| Artifact | Purpose |
|---|---|
| `burp-mcp-extension-<version>.jar` | Burp Suite extension (loopback bridge) |
| `burp-mcp-server-<version>.tar.gz` / `.zip` | Stdio MCP server, including production `node_modules` |

The installer downloads both, verifies `SHA256SUMS`, installs them under a stable prefix, and
registers the server with supported agents. No clone, JDK or Gradle is needed.

## Requirements

- Burp Suite Professional (Montoya API 2026.7)
- Node.js **>= 22.12** on `PATH`
- Linux/macOS: `curl`, `tar`, and `sha256sum` or `shasum`
- Windows: PowerShell 5.1+ (the built-in `Expand-Archive` is used)
- `gh` (GitHub CLI) only for private forks or mirrors

JDK 21 and Gradle are only required for [building from source](../README.md#build-from-source).

## One-command install

### Linux / macOS

```bash
curl -fsSL https://raw.githubusercontent.com/dcm2406/burp-mcp/main/scripts/install.sh | bash
```

Review the script first if your policy requires it:

```bash
curl -fsSLO https://raw.githubusercontent.com/dcm2406/burp-mcp/main/scripts/install.sh
less install.sh
bash install.sh
```

### Windows

```powershell
Invoke-WebRequest -UseBasicParsing https://github.com/dcm2406/burp-mcp/releases/latest/download/install.ps1 -OutFile install.ps1
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

### Private fork or mirror

Authenticate once with `gh auth login`, stream that repository's installer, and point
`BURP_MCP_REPO` at it so release downloads use the fork:

```bash
gh api repos/<owner>/<repo>/contents/scripts/install.sh \
  -H "Accept: application/vnd.github.raw" \
  | BURP_MCP_REPO=<owner>/<repo> bash
```

Windows:

```powershell
$env:BURP_MCP_REPO = "<owner>/<repo>"
gh release download --repo <owner>/<repo> --pattern install.ps1 --dir . --clobber
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

Set `BURP_MCP_REPO` again when you upgrade a private fork, or change the default in the fork's
copy of the installer.

### Options

`install.sh`:

| Option | Meaning |
|---|---|
| `--version vX.Y.Z` | Install a specific release (default: latest) |
| `--prefix DIR` | Install directory (default: `${XDG_DATA_HOME:-$HOME/.local/share}/burp-mcp`) |
| `--local` | Use `extension/build/libs` and `mcp-server/` from a checkout (development) |
| `--no-config` | Install files only; print manual registration commands |
| `--dry-run` | Print the resolved version and paths without changing anything |
| `--uninstall` | Remove installed files and the Claude Code user entry (leaves session data) |
| `-h`, `--help` | Usage |

When piping to `bash`, pass options after `-s --`:

```bash
curl -fsSL https://raw.githubusercontent.com/dcm2406/burp-mcp/main/scripts/install.sh \
  | bash -s -- --version v0.1.0 --no-config
```

`install.ps1` accepts `-Version`, `-Prefix`, `-Local`, `-NoConfig`, `-DryRun` and `-Uninstall`.

## What gets installed

Linux/macOS (`PREFIX` defaults to `~/.local/share/burp-mcp`):

```text
PREFIX/
  install.sh                                  # re-run for upgrade/uninstall
  bin/burp-mcp                                # convenience wrapper
  extension/burp-mcp-extension.jar            # load this in Burp
  extension/burp-mcp-extension-0.1.0.jar      # versioned copy
  server/dist/cli.js                          # MCP entrypoint
  server/node_modules/                        # production dependencies
  server/package.json
  server/VERSION
```

Windows (`PREFIX` defaults to `%LOCALAPPDATA%\burp-mcp`):

```text
PREFIX\
  install.ps1
  bin\burp-mcp.cmd
  extension\burp-mcp-extension.jar
  extension\burp-mcp-extension-0.1.0.jar
  server\dist\cli.js
  server\node_modules\
  server\package.json
  server\VERSION
```

The agent configuration points at `PREFIX/server/dist/cli.js`. Upgrades replace `server/` and
`extension/` in place, so the configured path stays valid.

## Finish setup

1. Burp → **Extensions → Installed → Add**, type **Java**, select the installed
   `extension/burp-mcp-extension.jar`.
2. Open the **Burp MCP** tab and enable the bridge.
3. Restart your agent session. The server is spawned per session, so an already-running session
   keeps the old process.

Confirm registration:

```bash
opencode mcp list
claude mcp list
```

## Manual install

Use this when you cannot run the installer, or need an offline install.

1. Download these assets from the GitHub release (tag `v0.1.0`, file names without the `v`):
   - `burp-mcp-extension-0.1.0.jar`
   - `burp-mcp-server-0.1.0.tar.gz` (Linux/macOS) or `burp-mcp-server-0.1.0.zip` (Windows)
   - `SHA256SUMS`
2. Verify the downloads:

   ```bash
   # Linux
   sha256sum -c SHA256SUMS --ignore-missing

   # macOS (only the artifacts downloaded above)
   grep -E 'burp-mcp-(extension|server)-0\.1\.0\.(jar|tar\.gz)' SHA256SUMS \
     | shasum -a 256 -c -
   ```

   On Windows:

   ```powershell
   Get-FileHash .\burp-mcp-extension-0.1.0.jar -Algorithm SHA256
   Get-FileHash .\burp-mcp-server-0.1.0.zip -Algorithm SHA256
   ```

   Compare the values with `SHA256SUMS`.
3. Extract the server and install the JAR:

   ```bash
   mkdir -p ~/.local/share/burp-mcp
   tar -xzf burp-mcp-server-0.1.0.tar.gz -C ~/.local/share/burp-mcp
   mkdir -p ~/.local/share/burp-mcp/extension
   cp burp-mcp-extension-0.1.0.jar ~/.local/share/burp-mcp/extension/
   ```

   On Windows, expand the zip so that `server\dist\cli.js` exists under
   `%LOCALAPPDATA%\burp-mcp`, then copy the JAR to `extension\burp-mcp-extension.jar`.
4. Register the server with your agent using one of the snippets below.

The server bundle already contains production `node_modules`; do not run `npm install`.

## Agent configuration

Replace `/path/to/node` with `command -v node` (Linux/macOS) or `(Get-Command node).Source`
(Windows), and `PREFIX` with the install directory.

### OpenCode

The installer uses the CLI, which writes the global config:

```bash
opencode mcp add --global burp -- /path/to/node PREFIX/server/dist/cli.js
```

Equivalent `~/.config/opencode/opencode.json` entry:

```json
{
  "mcp": {
    "servers": {
      "burp": {
        "type": "local",
        "command": [
          "/path/to/node",
          "/home/you/.local/share/burp-mcp/server/dist/cli.js"
        ]
      }
    }
  }
}
```

### Claude Code

```bash
claude mcp add --scope user burp -- /path/to/node PREFIX/server/dist/cli.js
```

Equivalent project `.mcp.json` entry:

```json
{
  "mcpServers": {
    "burp": {
      "command": "/path/to/node",
      "args": [
        "/home/you/.local/share/burp-mcp/server/dist/cli.js"
      ]
    }
  }
}
```

### Codex

```bash
codex mcp add burp -- /path/to/node PREFIX/server/dist/cli.js
```

Equivalent `~/.codex/config.toml` entry:

```toml
[mcp_servers.burp]
command = "/path/to/node"
args = ["/home/you/.local/share/burp-mcp/server/dist/cli.js"]
```

### Other stdio clients

Use the absolute Node path as the command and the absolute `cli.js` path as the only argument.
Do not set a working directory: the server locates `node_modules` and the Burp session descriptor
independently of `cwd`.

## Upgrade

Re-run the installer. It resolves the latest release, replaces the files under `PREFIX`, and
rewrites the agent entry to the same stable server path:

```bash
~/.local/share/burp-mcp/install.sh
```

Pin a version:

```bash
~/.local/share/burp-mcp/install.sh --version v0.1.0
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\burp-mcp\install.ps1"
```

After an upgrade:

1. Reload the extension in Burp (remove and add the new JAR, or use Burp's reload action).
2. Restart the agent session.

The JAR and server must be from the same release; mixing versions produces
`BRIDGE_INVALID_RESPONSE`.

## Uninstall

```bash
~/.local/share/burp-mcp/install.sh --uninstall
```

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File "$env:LOCALAPPDATA\burp-mcp\install.ps1" -Uninstall
```

The uninstaller removes `server/`, `extension/`, `bin/`, the stored installer copy, and the Claude
Code user entry. It deliberately leaves Burp session data in `PREFIX` (`active-session.json`,
`audit/`, `index/`) so an uninstall does not destroy evidence. Remove the OpenCode/Codex entry
manually if present, remove the extension from Burp, and delete `PREFIX` itself if you want a full
purge.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `cannot resolve the latest release` | Check network/proxy, or pass `--version v0.1.0`. For a private fork, install `gh` and run `gh auth login`. |
| `gh: command not found` | Only needed for a private fork. Public installs use `curl`/`Invoke-WebRequest`. |
| `cannot download ...` / `404` | For a private fork, `curl` cannot access private release assets; authenticate with `gh auth login` and use the private-fork commands. Otherwise retry the download. |
| `checksum mismatch` | Delete the downloaded files and retry. Do not bypass the check; a partial download or proxy error can corrupt the bundle. |
| `Node.js >= 22.12 is required` | Upgrade Node; the server and its dependencies require modern ESM and web APIs. |
| `BRIDGE_INVALID_RESPONSE` | The extension and server are from different builds, the bridge is disabled, or Burp was reloaded without restarting the agent session. Reinstall from one release, reload the extension, restart the agent. |
| `burp` listed but tools fail | Open the Burp MCP tab and confirm the bridge is enabled; check the Burp extension output for the session descriptor path. |
| Windows execution policy blocks the script | Use `powershell -ExecutionPolicy Bypass -File .\install.ps1`, or unblock the file. |

For deeper diagnosis, see [`mcp-setup.md`](mcp-setup.md) and [`smoke-test.md`](smoke-test.md).
