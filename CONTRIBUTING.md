# Contributing

## Build and test

```bash
./scripts/bootstrap.sh   # optional: portable JDK/Node/Gradle into .tools/
./scripts/build.sh       # full pipeline: tests, fat JAR, MCP build/tests, npm pack
./scripts/test.sh        # tests only
```

Windows equivalents: `.\scripts\build.ps1` and `.\scripts\test.ps1`. Requirements are JDK 21 and
Node.js >= 22.12; the Gradle wrapper supplies Gradle itself.

## Rules of thumb

- The extension JAR and `mcp-server/dist/` are one artifact pair. Any change that crosses the
  bridge must update both sides in the same commit, because the server validates responses with
  strict schemas and reports `BRIDGE_INVALID_RESPONSE` on a mismatch.
- A new or changed bridge endpoint needs all four: `contracts/bridge-api.yaml`, the Java handler,
  the TypeScript client/tool, and tests on both sides.
- Security-sensitive behaviour changes (authentication, discovery, file permissions, audit
  redaction, URL validation) must be reflected in `docs/threat-model.md`.
- New documentation and code comments are written in English. Historical notes under `plans/`
  are kept in Vietnamese.
- Do not commit tokens, session descriptors, real pentest data, build output (`dist/`, `build/`,
  `node_modules/`) or machine-specific absolute paths. Use `<BURP_MCP_ROOT>` in examples.

## Pull requests

CI runs the full suite on Ubuntu and Windows. Packaging invariants (`scripts/ci-checks.sh`) run
on Linux and must stay green. Please keep commits focused and describe the trust-boundary impact
of behavioural changes.
