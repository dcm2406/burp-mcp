import { access, mkdtemp, rm, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { sendJson, startMockBridge, type MockBridge } from "./helpers/mock-bridge.js";

const cleanups: Array<() => Promise<void>> = [];

afterEach(async () => {
  await Promise.all(cleanups.splice(0).map((cleanup) => cleanup()));
});

function childEnvironment(extra: Record<string, string>): Record<string, string> {
  const inherited = Object.fromEntries(
    Object.entries(process.env).filter((entry): entry is [string, string] => entry[1] !== undefined)
  );
  return { ...inherited, ...extra };
}

async function descriptorFor(bridge: MockBridge): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "burp-mcp-stdio-"));
  cleanups.push(() => rm(directory, { recursive: true, force: true }));
  const sessionFile = join(directory, "session.json");
  await writeFile(
    sessionFile,
    JSON.stringify({
      schemaVersion: 1,
      sessionId: "stdio-session",
      baseUrl: bridge.baseUrl,
      token: "stdio-token-0123456789"
    })
  );
  return sessionFile;
}

interface JsonRpcFrame {
  jsonrpc: string;
  id?: number | string;
  method?: string;
  result?: { tools?: unknown[]; isError?: boolean; structuredContent?: unknown };
}

describe("compiled stdio server", () => {
  it("speaks only JSON-RPC on stdout and never leaks diagnostics there", async () => {
    const cli = resolve("dist/cli.js");
    await access(cli);

    const bridge = await startMockBridge((request, response) => {
      expect(request.authorization).toBe("Bearer stdio-token-0123456789");
      sendJson(response, 200, {
        connected: true,
        sessionId: "stdio-session",
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
    const sessionFile = await descriptorFor(bridge);

    const child = spawn(process.execPath, [cli], {
      env: childEnvironment({ BURP_MCP_SESSION_FILE: sessionFile }),
      stdio: ["pipe", "pipe", "pipe"]
    });
    cleanups.push(() => {
      child.kill();
      return Promise.resolve();
    });

    const stdoutChunks: Buffer[] = [];
    const stderrChunks: Buffer[] = [];
    child.stdout.on("data", (chunk: Buffer) => stdoutChunks.push(chunk));
    child.stderr.on("data", (chunk: Buffer) => stderrChunks.push(chunk));

    const pending = new Map<number, (reply: JsonRpcFrame) => void>();
    const pendingRejects = new Set<(reason: Error) => void>();
    let buffer = "";
    child.stdout.on("data", (chunk: Buffer) => {
      buffer += chunk.toString("utf8");
      let index = buffer.indexOf("\n");
      while (index !== -1) {
        const line = buffer.slice(0, index).trim();
        buffer = buffer.slice(index + 1);
        if (line.length > 0) {
          const frame = JSON.parse(line) as JsonRpcFrame;
          if (typeof frame.id === "number" && pending.has(frame.id)) {
            const settle = pending.get(frame.id);
            pending.delete(frame.id);
            settle?.(frame);
          }
        }
        index = buffer.indexOf("\n");
      }
    });
    let exited = false;
    child.once("exit", (code) => {
      exited = true;
      const error = new Error(`child exited with ${code}`);
      for (const rejectPending of pendingRejects) rejectPending(error);
      pendingRejects.clear();
      pending.clear();
    });
    const reply = (id: number): Promise<JsonRpcFrame> =>
      new Promise((resolvePromise, rejectPromise) => {
        if (exited) {
          rejectPromise(new Error("child exited before the reply was received"));
          return;
        }
        pending.set(id, resolvePromise);
        pendingRejects.add(rejectPromise);
      });

    const initialize = {
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2026-07-28",
        capabilities: {},
        clientInfo: { name: "stdio-test", version: "0.1.0" }
      }
    };
    const listTools = { jsonrpc: "2.0", id: 2, method: "tools/list" };
    const callStatus = {
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: { name: "burp_status", arguments: {} }
    };
    const initialized = { jsonrpc: "2.0", method: "notifications/initialized" };

    child.stdin.write(`${JSON.stringify(initialize)}\n`);
    await reply(1);
    child.stdin.write(
      `${JSON.stringify(initialized)}\n${JSON.stringify(listTools)}\n`
    );
    const listed = await reply(2);
    expect(listed.result?.tools).toHaveLength(8);

    child.stdin.write(`${JSON.stringify(callStatus)}\n`);
    const called = await reply(3);
    expect(called.result?.isError).not.toBe(true);
    expect(called.result?.structuredContent).toMatchObject({ ok: true, data: { burpVersion: "2026.7" } });
    expect(bridge.requests).toHaveLength(1);

    child.stdin.end();
    await new Promise<void>((resolvePromise) => child.once("exit", () => resolvePromise()));

    // Every stdout byte must be a newline-delimited JSON-RPC frame.
    const stdout = Buffer.concat(stdoutChunks).toString("utf8");
    const lines = stdout.split(/\r?\n/).filter((line) => line.length > 0);
    expect(lines.length).toBeGreaterThanOrEqual(3);
    for (const line of lines) {
      const frame = JSON.parse(line) as JsonRpcFrame;
      expect(frame.jsonrpc).toBe("2.0");
    }
    const stderr = Buffer.concat(stderrChunks).toString("utf8");
    expect(stderr).not.toContain("Bearer");
    expect(stderr).not.toContain(sessionFile);
  });
});
