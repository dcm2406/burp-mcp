import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { BridgeClient } from "../src/bridge-client.js";
import { BurpMcpError } from "../src/errors.js";
import { sendJson, startMockBridge, type MockBridge } from "./helpers/mock-bridge.js";

const cleanups: Array<() => Promise<void>> = [];

afterEach(async () => {
  await Promise.all(cleanups.splice(0).map((cleanup) => cleanup()));
});

async function clientFor(bridge: MockBridge, timeoutMs = 1_000): Promise<BridgeClient> {
  const directory = await mkdtemp(join(tmpdir(), "burp-mcp-client-"));
  cleanups.push(() => rm(directory, { recursive: true, force: true }));
  const sessionFile = join(directory, "session.json");
  await writeFile(
    sessionFile,
    JSON.stringify({
      schemaVersion: 1,
      sessionId: "test-session",
      baseUrl: bridge.baseUrl,
      token: "test-token-0123456789"
    })
  );
  return new BridgeClient({ discovery: { sessionFile }, timeoutMs });
}

describe("BridgeClient", () => {
  it("discovers the bridge and sends bearer authentication", async () => {
    const bridge = await startMockBridge((request, response) => {
      expect(request.path).toBe("/v1/status");
      expect(request.authorization).toBe("Bearer test-token-0123456789");
      sendJson(response, 200, {
        connected: true,
        sessionId: "test-session",
        extensionVersion: "0.1.0",
        burpVersion: "2026.7",
        startedAt: "2026-10-20T12:00:00Z",
        capabilities: ["proxyHistory", "sendHttpRequest", "sendToRepeater", "sendHttpRequestBatch", "collaborator"],
        limits: {
          maxRawBytes: 10_485_760,
          maxPageSize: 200,
          maxBatchItems: 50,
          maxBatchConcurrency: 10,
          maxBatchInputBytes: 8 * 1024 * 1024,
          maxBatchOutputBytes: 8 * 1024 * 1024
        }
      });
    });
    cleanups.push(() => bridge.close());
    const client = await clientFor(bridge);

    await expect(client.status()).resolves.toMatchObject({ burpVersion: "2026.7" });
    expect(bridge.requests).toHaveLength(1);
  });

  it("maps bridge error envelopes to safe typed errors", async () => {
    const bridge = await startMockBridge((_request, response) => {
      sendJson(response, 410, {
        error: { code: "STALE_REFERENCE", message: "Reference belongs to an old session", requestId: "request-7" }
      });
    });
    cleanups.push(() => bridge.close());
    const client = await clientFor(bridge);

    const error = await client.getProxyMessage({ ref: "old-ref" }).catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(BurpMcpError);
    expect(error).toMatchObject({ code: "STALE_REFERENCE", requestId: "request-7", status: 410 });
  });

  it("maps the flat MCP history input to the nested bridge contract", async () => {
    const bridge = await startMockBridge((request, response) => {
      expect(request.body).toEqual({
        pageSize: 25,
        cursor: "cursor-1",
        filter: { host: "example.test", method: "POST", statusCodes: [201] }
      });
      sendJson(response, 200, { items: [], nextCursor: null, returned: 0, malformedItemCount: 0 });
    });
    cleanups.push(() => bridge.close());
    const client = await clientFor(bridge);

    await expect(
      client.proxyHistory({ host: "example.test", method: "POST", statusCodes: [201], cursor: "cursor-1", limit: 25 })
    ).resolves.toMatchObject({ returned: 0 });
  });

  it("does not retry a side-effecting send after an HTTP failure", async () => {
    const bridge = await startMockBridge((_request, response) => {
      sendJson(response, 503, { error: { code: "QUEUE_FULL", message: "Bridge queue is full", requestId: "r1" } });
    });
    cleanups.push(() => bridge.close());
    const client = await clientFor(bridge);

    await expect(
      client.sendHttpRequest({
        service: { host: "example.test", port: 443, secure: true },
        request: { encoding: "base64", data: "R0VUIC8gSFRUUC8xLjENCg0K" }
      })
    ).rejects.toMatchObject({ code: "QUEUE_FULL" });
    expect(bridge.requests).toHaveLength(1);
  });

  it("aborts at the fixed bridge timeout and does not retry", async () => {
    const bridge = await startMockBridge(async (_request, response) => {
      await new Promise((resolve) => setTimeout(resolve, 100));
      if (!response.destroyed) sendJson(response, 200, { version: "late" });
    });
    cleanups.push(() => bridge.close());
    const client = await clientFor(bridge, 20);

    await expect(client.status()).rejects.toMatchObject({ code: "BRIDGE_TIMEOUT" });
    expect(bridge.requests).toHaveLength(1);
  });

  it("rejects malformed successful bridge data", async () => {
    const bridge = await startMockBridge((_request, response) => {
      sendJson(response, 200, { items: [{ method: "GET" }] });
    });
    cleanups.push(() => bridge.close());
    const client = await clientFor(bridge);

    await expect(client.proxyHistory({ limit: 50 })).rejects.toMatchObject({ code: "BRIDGE_INVALID_RESPONSE" });
  });
});
