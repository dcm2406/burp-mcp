#!/usr/bin/env node

import { serveStdio, StdioServerTransport } from "@modelcontextprotocol/server/stdio";

import { MAX_STDIO_BUFFER_BYTES, PACKAGE_NAME, PACKAGE_VERSION } from "./constants.js";
import { createBurpMcpServer } from "./server.js";

function reportFatal(error: unknown): void {
  const message = error instanceof Error ? error.message : String(error);
  process.stderr.write(`${PACKAGE_NAME} ${PACKAGE_VERSION} fatal error: ${message}\n`);
}

try {
  serveStdio(() => createBurpMcpServer(), {
    transport: new StdioServerTransport(process.stdin, process.stdout, { maxBufferSize: MAX_STDIO_BUFFER_BYTES }),
    onerror: reportFatal
  });
} catch (error) {
  reportFatal(error);
  process.exitCode = 1;
}
