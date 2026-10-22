import { Client, InMemoryTransport } from "@modelcontextprotocol/client";
import { afterEach, describe, expect, it } from "vitest";

import type { BridgeApi } from "../src/bridge-client.js";
import { BurpMcpError } from "../src/errors.js";
import { createBurpMcpServer } from "../src/server.js";

const clients: Client[] = [];

afterEach(async () => {
  await Promise.all(clients.splice(0).map((client) => client.close()));
});

function fakeBridge(): BridgeApi {
  const request = {
    encoding: "base64" as const,
    data: "R0VUIC8gSFRUUC8xLjENCg0K",
    byteLength: 18,
    returnedByteLength: 18,
    truncated: false,
    sha256: "6b81f0f76f2f1dd1b68d0c207b36d587b9e329e722c5d148298a055b23b309ee"
  };
  const response = {
    encoding: "base64" as const,
    data: "SFRUUC8xLjEgMjAwIE9LDQoNCg==",
    byteLength: 19,
    returnedByteLength: 19,
    truncated: false,
    sha256: "b0578d8d1f3be10e0d9a950150560852b576eefc905dc42075e93ed7da4795bc"
  };
  return {
    status: async () => ({
      connected: true,
      sessionId: "session-1",
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
    }),
    proxyHistory: async () => ({
      items: [{
        ref: "ref-1",
        burpHistoryId: 1,
        time: "2026-10-20T12:00:00Z",
        method: "GET",
        url: "https://example.test/",
        service: { host: "example.test", port: 443, secure: true },
        statusCode: 200,
        mimeType: "HTML",
        hasResponse: true,
        edited: false,
        listenerPort: 8080,
        requestByteLength: 18,
        responseByteLength: 19
      }],
      nextCursor: null,
      returned: 1,
      malformedItemCount: 0
    }),
    getProxyMessage: async ({ ref }) => ({
      ref,
      burpHistoryId: 1,
      time: "2026-10-20T12:00:00Z",
      method: "GET",
      url: "https://example.test/",
      service: { host: "example.test", port: 443, secure: true },
      statusCode: 200,
      request,
      response,
      hasResponse: true
    }),
    sendHttpRequest: async () => ({
      service: { host: "example.test", port: 443, secure: true },
      request,
      response,
      hasResponse: true,
      statusCode: 200
    }),
    sendHttpRequestBatch: async (input) => ({
      results: input.items.map(() => ({
        statusCode: 200,
        request: {
          byteLength: 24,
          sha256: "6b81f0f76f2f1dd1b68d0c207b36d587b9e329e722c5d148298a055b23b309ee"
        },
        response: {
          byteLength: 19,
          sha256: "b0578d8d1f3be10e0d9a950150560852b576eefc905dc42075e93ed7da4795bc"
        },
        hasResponse: true,
        truncated: false,
        omitted: false,
        durationMs: 3
      })),
      succeeded: input.items.length,
      failed: 0
    }),
    collaboratorPayload: async (input) => ({
      ref: "collab-ref-1",
      payloadId: "payload1id",
      payload: "payload1id.burpcollaborator.net",
      server: "burpcollaborator.net",
      ...(input.customData === undefined ? {} : { customData: input.customData })
    }),
    collaboratorInteractions: async () => ({
      interactions: [
        {
          ref: "interaction-ref-1",
          type: "HTTP" as const,
          time: "2026-10-21T10:00:00Z",
          clientIp: "10.0.0.9",
          clientPort: 40000,
          customData: "sqli:login:user",
          http: {
            method: "GET",
            path: "/",
            request: {
              data: "R0VUIC8gSFRUUC8xLjENCg0K",
              byteLength: 18,
              sha256: "6b81f0f76f2f1dd1b68d0c207b36d587b9e329e722c5d148298a055b23b309ee"
            },
            response: {
              data: "SFRUUC8xLjEgMjAwIE9LDQoNCg==",
              byteLength: 19,
              sha256: "b0578d8d1f3be10e0d9a950150560852b576eefc905dc42075e93ed7da4795bc"
            }
          }
        }
      ],
      total: 1,
      newCount: 1
    }),
    sendToRepeater: async ({ tabName }) => ({ accepted: true, ...(tabName === undefined ? {} : { tabName }) })
  };
}

async function connectedClient(bridge: BridgeApi): Promise<Client> {
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  const server = createBurpMcpServer(bridge);
  const client = new Client({ name: "burp-mcp-test", version: "0.1.0" });
  clients.push(client);
  await server.connect(serverTransport);
  await client.connect(clientTransport);
  return client;
}

describe("MCP tools", () => {
  it("lists exactly the five public tools with side-effect annotations", async () => {
    const client = await connectedClient(fakeBridge());
    const result = await client.listTools();

    expect(result.tools.map(({ name }) => name)).toEqual([
      "burp_status",
      "burp_proxy_history",
      "burp_get_proxy_message",
      "burp_send_http_request",
      "burp_collaborator_payload",
      "burp_collaborator_interactions",
      "burp_send_http_batch",
      "burp_send_to_repeater"
    ]);
    expect(result.tools.find(({ name }) => name === "burp_send_http_request")?.annotations).toMatchObject({
      readOnlyHint: false,
      destructiveHint: true,
      idempotentHint: false,
      openWorldHint: true
    });
  });

  it("sends a batch through the bridge and reports succeeded/failed counts", async () => {
    const client = await connectedClient(fakeBridge());
    const result = await client.callTool({
      name: "burp_send_http_batch",
      arguments: {
        items: [
          { service: { host: "127.0.0.1", port: 8888, secure: false }, request: { encoding: "base64", data: "R0VUIC8gc21va2UgSFRUUC8xLjENCkhvc3Q6IDEyNy4wLjAuMTo4ODg4DQoNCg==" } },
          { service: { host: "127.0.0.1", port: 8888, secure: false }, request: { encoding: "base64", data: "R0VUIC8gc21va2UyIEhUVFAvMS4xDQpIb3N0OiAxMjcuMC4wLjE6ODg4OA0KDQo=" } }
        ],
        concurrency: 2,
        responseMode: "summary"
      }
    });

    expect(result.isError).not.toBe(true);
    expect(result.structuredContent).toMatchObject({
      ok: true,
      data: { succeeded: 2, failed: 0 }
    });
    expect((result.structuredContent as { data: { results: unknown[] } }).data.results).toHaveLength(2);
  });

  it("generates a Collaborator payload and polls interactions through the bridge", async () => {
    const client = await connectedClient(fakeBridge());
    const generated = await client.callTool({
      name: "burp_collaborator_payload",
      arguments: { customData: "sqli12" }
    });

    expect(generated.isError).not.toBe(true);
    expect(generated.structuredContent).toMatchObject({
      ok: true,
      data: { payloadId: "payload1id", payload: "payload1id.burpcollaborator.net", customData: "sqli12" }
    });

    const polled = await client.callTool({
      name: "burp_collaborator_interactions",
      arguments: { ref: "collab-ref-1" }
    });

    expect(polled.isError).not.toBe(true);
    expect(polled.structuredContent).toMatchObject({
      ok: true,
      data: { total: 1, newCount: 1 }
    });
  });

  it("calls a tool and returns structured content plus a text summary", async () => {
    const client = await connectedClient(fakeBridge());
    const result = await client.callTool({ name: "burp_status", arguments: {} });

    expect(result.isError).not.toBe(true);
    expect(result.structuredContent).toEqual({
      ok: true,
      data: {
        connected: true,
        sessionId: "session-1",
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
      }
    });
    expect(result.content).toEqual([
      { type: "text", text: "Burp MCP bridge is reachable (Burp version: 2026.7)." }
    ]);
  });

  it("maps bridge failures to isError without throwing a protocol error", async () => {
    const bridge = fakeBridge();
    bridge.status = async () => {
      throw new BurpMcpError("BRIDGE_AUTH_FAILED", "Session token expired.");
    };
    const client = await connectedClient(bridge);
    const result = await client.callTool({ name: "burp_status", arguments: {} });

    expect(result.isError).toBe(true);
    expect(result.structuredContent).toEqual({
      ok: false,
      error: { code: "BRIDGE_AUTH_FAILED", message: "Session token expired." }
    });
  });
});
