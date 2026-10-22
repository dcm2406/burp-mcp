# Testing

## Automated suite

```powershell
.\scripts\test.ps1
```

The suite must not load or parse existing `.burp` files. Java tests use an in-memory `BurpGateway`; TypeScript tests use a loopback mock bridge.

Current counts: 41 Java tests (auth + loopback bind, history filter/paginate/raw ref, `413` cap, send + repeater, opaque reference/cursor tampering, token rotation + kill switch, audit redaction, descriptor ACL owner-only + atomic write + delete, history index filter/order/pagination/vanish/malformed/owner-only/dispose, index-vs-live content parity and refresh, batch size/budget/concurrency validation + ordered execution + summary/full modes + per-item failure + real parallelism + one-audit-line, plus `@EnabledOnOs` POSIX/Windows owner-only tests; the Windows-only ACL test skips on POSIX) and 35 TypeScript tests (session discovery and unsafe URL rejection, bridge client envelope mapping and no-retry/timeout behavior, tool schema limits including batch bounds, canonical base64, Collaborator customData pattern and ref/payload exclusivity, tool registration and `isError` mapping, batch and Collaborator tool call-through, and stdio JSON-RPC purity). Test sources are type-checked with `npm run typecheck:test`.

## Packaging verification

```powershell
.\scripts\build.ps1
```

Verify:

- The extension JAR contains application classes and Jackson dependencies.
- The extension JAR does not contain `burp/api/montoya/**`.
- The npm tarball contains `dist`, package metadata and executable entrypoint only.
- MCP stdio output contains JSON-RPC only; diagnostics go to stderr.

## Manual smoke test against live Burp

Automated tests never touch a live Burp instance, so the integration smoke test is a
separate manual gate. The full runbook, expected results and troubleshooting table
live in `docs/smoke-test.md`.

Short version:

1. Load `extension/build/libs/burp-mcp-extension-0.1.0-all.jar` into Burp Professional and confirm the **Burp MCP** tab shows an enabled `127.0.0.1` listener.
2. Run the contract checks against the live bridge (the script reads the session descriptor itself and never prints the token):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

3. Walk the GUI-only checks in the runbook: Repeater tab without sending, audit log redaction, kill switch, unload, re-enable rotation, and the five tools through OpenCode.
