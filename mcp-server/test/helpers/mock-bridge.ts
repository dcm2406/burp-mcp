import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";

export interface CapturedRequest {
  method: string;
  path: string;
  authorization?: string;
  body: unknown;
}

export interface MockBridge {
  baseUrl: string;
  requests: CapturedRequest[];
  close(): Promise<void>;
}

export type MockBridgeHandler = (
  request: CapturedRequest,
  response: ServerResponse
) => void | Promise<void>;

async function readJson(request: IncomingMessage): Promise<unknown> {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  if (chunks.length === 0) return undefined;
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
}

export function sendJson(response: ServerResponse, status: number, value: unknown): void {
  const body = JSON.stringify(value);
  response.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(body) });
  response.end(body);
}

export async function startMockBridge(handler: MockBridgeHandler): Promise<MockBridge> {
  const requests: CapturedRequest[] = [];
  const server = createServer(async (incoming, response) => {
    try {
      const captured: CapturedRequest = {
        method: incoming.method ?? "",
        path: incoming.url ?? "",
        ...(incoming.headers.authorization === undefined ? {} : { authorization: incoming.headers.authorization }),
        body: await readJson(incoming)
      };
      requests.push(captured);
      await handler(captured, response);
    } catch (error) {
      sendJson(response, 500, { error: { code: "MOCK_ERROR", message: String(error), requestId: "mock" } });
    }
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });
  const address = server.address() as AddressInfo;

  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    requests,
    close: async () => {
      await new Promise<void>((resolve, reject) => {
        server.close((error) => (error === undefined ? resolve() : reject(error)));
      });
    }
  };
}
