# Security policy

## Supported versions

Only the latest commit on `main` is supported. This project is pre-1.0; security fixes land on
`main` and are not backported.

## Reporting a vulnerability

Do **not** open a public issue. Use GitHub private vulnerability reporting:

<https://github.com/dcm2406/burp-mcp/security/advisories/new>

Include:

- affected commit or version,
- reproduction steps and required configuration,
- impact (what an attacker gains, which trust boundary is crossed).

Expect an acknowledgement within 7 days. Please redact real bearer tokens, session descriptors
and target data from reports; `token` and `sessionId` values are credentials.

## Scope and threat model

[`docs/threat-model.md`](docs/threat-model.md) lists assets, trust boundaries and accepted risks.
The properties that reports should preserve:

- The bridge binds to literal `127.0.0.1` only, on an ephemeral port.
- Every request is authenticated with a per-session 256-bit bearer token, compared in constant time.
- The session descriptor is owner-only and deleted when the bridge stops.
- References and cursors are session-bound and become invalid after restart or re-enable.
- The audit log records metadata only — no tokens, headers, bodies or full URLs.

The bridge deliberately does **not** enforce a target scope: it sends requests to any host the
operator points it at. That is an accepted design trade-off, not a vulnerability. Reports about
missing target authorization will be closed as out of scope.

## Dependency hygiene

`npm audit` runs clean at every release; the JAR bundles Jackson and `sqlite-jdbc` and does not
bundle the Montoya API. Report suspected dependency issues through the same private channel.
