# Threat model

## Protected assets

- Session cookies, bearer tokens and PII contained in Burp traffic.
- Authority to send arbitrary HTTP requests through Burp.
- Integrity of the selected destination and raw HTTP bytes.
- Availability of Burp and tested targets.

## Trust boundaries

| Boundary | Assumption |
|---|---|
| OpenCode/model → MCP process | Tool arguments are untrusted and fully validated. |
| MCP process → loopback bridge | Authenticated using the short-lived descriptor token. |
| Extension → target | Unrestricted by design in the MVP; operator authorization is required. |
| Local machine → cloud model | Raw tool output may leave the machine. |

## Controls

- Literal IPv4 loopback bind with random port.
- Random 256-bit bearer token, constant-time comparison and rotation.
- Owner-restricted, atomically replaced session descriptor.
- No CORS; strict Host, method, route and content-type checks.
- Bounded worker queue and 10 MiB decoded raw payload limit.
- Signed session-bound history references and cursors.
- No automatic retry for HTTP send or Repeater actions.
- Metadata-only rotating audit logs.
- Burp UI kill switch and unload cleanup.

## Residual risks

1. **Cloud disclosure:** raw traffic requested by a tool can be sent to the configured cloud model.
2. **Unrestricted targets:** the MVP does not enforce Burp scope or an allowlist.
3. **In-flight actions:** killing the bridge cannot reliably cancel a request already executing inside Burp.
4. **Local compromise:** malware running as the same Windows user can read the descriptor and call the bridge.
5. **History scale:** querying 10k–100k entries materializes Burp history until the SQLite phase is implemented.

## Operator rules

- Use only on explicitly authorized targets.
- Disable the bridge when not actively using MCP.
- Do not expose the session descriptor or audit directory through sync/share tools.
- Review the selected cloud model provider's retention policy before retrieving raw traffic.
