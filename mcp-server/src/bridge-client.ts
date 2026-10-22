import * as z from "zod/v4";

import {
  DEFAULT_BRIDGE_TIMEOUT_MS,
  MAX_BRIDGE_JSON_BYTES,
  MAX_RAW_BYTES
} from "./constants.js";
import { discoverBridge, type DiscoveryOptions } from "./discovery.js";
import { BurpMcpError } from "./errors.js";
import {
  decodedBase64Size,
  ProxyHistoryDataSchema,
  ProxyMessageDataSchema,
  RepeaterDataSchema,
  SendHttpRequestBatchDataSchema,
  CollaboratorInteractionsDataSchema,
  CollaboratorPayloadDataSchema,
  SendHttpResponseDataSchema,
  StatusDataSchema,
  type GetProxyMessageInput,
  type ProxyHistoryData,
  type ProxyHistoryInput,
  type ProxyMessageData,
  type RepeaterData,
  type SendHttpRequestBatchData,
  type SendHttpRequestBatchInput,
  type CollaboratorInteractionsData,
  type CollaboratorInteractionsInput,
  type CollaboratorPayloadData,
  type CollaboratorPayloadInput,
  type SendHttpRequestInput,
  type SendHttpResponseData,
  type SendToRepeaterInput,
  type StatusData
} from "./schemas.js";

const BridgeErrorEnvelopeSchema = z.object({
  error: z.object({
    code: z.string().min(1).max(128),
    message: z.string().min(1).max(2048),
    requestId: z.string().min(1).max(1024).optional()
  })
});

export interface BridgeApi {
  status(): Promise<StatusData>;
  proxyHistory(input: ProxyHistoryInput): Promise<ProxyHistoryData>;
  getProxyMessage(input: GetProxyMessageInput): Promise<ProxyMessageData>;
  sendHttpRequest(input: SendHttpRequestInput): Promise<SendHttpResponseData>;
  sendHttpRequestBatch(input: SendHttpRequestBatchInput): Promise<SendHttpRequestBatchData>;
  collaboratorPayload(input: CollaboratorPayloadInput): Promise<CollaboratorPayloadData>;
  collaboratorInteractions(input: CollaboratorInteractionsInput): Promise<CollaboratorInteractionsData>;
  sendToRepeater(input: SendToRepeaterInput): Promise<RepeaterData>;
}

export interface BridgeClientOptions {
  discovery?: DiscoveryOptions;
  fetch?: typeof globalThis.fetch;
  timeoutMs?: number;
}

interface RequestOptions<T> {
  method: "GET" | "POST";
  path: string;
  schema: z.ZodType<T>;
  body?: unknown;
}

async function readBoundedJson(response: Response, requireValidJson: boolean): Promise<unknown> {
  const declaredLength = response.headers.get("content-length");
  if (declaredLength !== null && Number(declaredLength) > MAX_BRIDGE_JSON_BYTES) {
    throw new BurpMcpError("PAYLOAD_TOO_LARGE", "The Burp bridge JSON response exceeded the safe envelope limit.");
  }

  if (response.body === null) {
    if (!requireValidJson) return undefined;
    throw new BurpMcpError("BRIDGE_INVALID_RESPONSE", "The Burp bridge returned an empty response.");
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_BRIDGE_JSON_BYTES) {
        await reader.cancel();
        throw new BurpMcpError("PAYLOAD_TOO_LARGE", "The Burp bridge JSON response exceeded the safe envelope limit.");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  if (total === 0 && !requireValidJson) return undefined;
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    return JSON.parse(text) as unknown;
  } catch (error) {
    if (!requireValidJson) return undefined;
    if (error instanceof BurpMcpError) throw error;
    throw new BurpMcpError("BRIDGE_INVALID_RESPONSE", "The Burp bridge returned invalid UTF-8 JSON.", {
      cause: error
    });
  }
}

function totalDecodedRawBytes(value: unknown): number {
  let total = 0;
  const stack: unknown[] = [value];
  while (stack.length > 0) {
    const current = stack.pop();
    if (current === null || typeof current !== "object") continue;
    if (Array.isArray(current)) {
      stack.push(...current);
      continue;
    }

    const record = current as Record<string, unknown>;
    if (record.encoding === "base64" && typeof record.data === "string") {
      const size = decodedBase64Size(record.data);
      if (size === undefined) {
        throw new BurpMcpError("INVALID_BASE64", "The Burp bridge returned malformed Base64 raw data.");
      }
      total += size;
      if (total > MAX_RAW_BYTES) return total;
      continue;
    }
    stack.push(...Object.values(record));
  }
  return total;
}

export class BridgeClient implements BridgeApi {
  readonly #discovery: DiscoveryOptions;
  readonly #fetch: typeof globalThis.fetch;
  readonly #timeoutMs: number;

  constructor(options: BridgeClientOptions = {}) {
    this.#discovery = options.discovery ?? {};
    this.#fetch = options.fetch ?? globalThis.fetch;
    this.#timeoutMs = options.timeoutMs ?? DEFAULT_BRIDGE_TIMEOUT_MS;
    if (!Number.isInteger(this.#timeoutMs) || this.#timeoutMs < 1) {
      throw new RangeError("timeoutMs must be a positive integer");
    }
  }

  async status(): Promise<StatusData> {
    return await this.#request({ method: "GET", path: "/v1/status", schema: StatusDataSchema });
  }

  async proxyHistory(input: ProxyHistoryInput): Promise<ProxyHistoryData> {
    const { cursor, limit, ...filter } = input;
    const body = {
      pageSize: limit,
      ...(cursor === undefined ? {} : { cursor }),
      ...(Object.keys(filter).length === 0 ? {} : { filter })
    };
    return await this.#request({
      method: "POST",
      path: "/v1/proxy-history/query",
      body,
      schema: ProxyHistoryDataSchema
    });
  }

  async getProxyMessage(input: GetProxyMessageInput): Promise<ProxyMessageData> {
    return await this.#request({
      method: "GET",
      path: `/v1/proxy-history/${encodeURIComponent(input.ref)}`,
      schema: ProxyMessageDataSchema
    });
  }

  async sendHttpRequest(input: SendHttpRequestInput): Promise<SendHttpResponseData> {
    return await this.#request({
      method: "POST",
      path: "/v1/http/send",
      body: input,
      schema: SendHttpResponseDataSchema
    });
  }

  async sendHttpRequestBatch(input: SendHttpRequestBatchInput): Promise<SendHttpRequestBatchData> {
    return await this.#request({
      method: "POST",
      path: "/v1/http/send-batch",
      body: input,
      schema: SendHttpRequestBatchDataSchema
    });
  }

  async collaboratorPayload(input: CollaboratorPayloadInput): Promise<CollaboratorPayloadData> {
    return await this.#request({
      method: "POST",
      path: "/v1/collaborator/payload",
      body: input,
      schema: CollaboratorPayloadDataSchema
    });
  }

  async collaboratorInteractions(input: CollaboratorInteractionsInput): Promise<CollaboratorInteractionsData> {
    return await this.#request({
      method: "POST",
      path: "/v1/collaborator/interactions",
      body: input,
      schema: CollaboratorInteractionsDataSchema
    });
  }

  async sendToRepeater(input: SendToRepeaterInput): Promise<RepeaterData> {
    return await this.#request({
      method: "POST",
      path: "/v1/repeater",
      body: input,
      schema: RepeaterDataSchema
    });
  }

  async #request<T>(options: RequestOptions<T>): Promise<T> {
    const session = await discoverBridge(this.#discovery);
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.#timeoutMs);
    timeout.unref?.();

    let response: Response;
    try {
      response = await this.#fetch(`${session.baseUrl}${options.path}`, {
        method: options.method,
        headers: {
          accept: "application/json",
          authorization: `Bearer ${session.token}`,
          ...(options.body === undefined ? {} : { "content-type": "application/json" })
        },
        ...(options.body === undefined ? {} : { body: JSON.stringify(options.body) }),
        redirect: "error",
        signal: controller.signal
      });
    } catch (error) {
      if (controller.signal.aborted) {
        throw new BurpMcpError(
          "BRIDGE_TIMEOUT",
          `The Burp bridge did not respond within ${this.#timeoutMs} ms. The action was not retried.`,
          { cause: error }
        );
      }
      throw new BurpMcpError(
        "BRIDGE_UNAVAILABLE",
        "The local Burp bridge is unavailable. Confirm that the extension bridge is enabled.",
        { cause: error }
      );
    } finally {
      clearTimeout(timeout);
    }

    const json = await readBoundedJson(response, response.ok);
    if (!response.ok) {
      const envelope = BridgeErrorEnvelopeSchema.safeParse(json);
      if (envelope.success) {
        throw new BurpMcpError(envelope.data.error.code, envelope.data.error.message, {
          status: response.status,
          ...(envelope.data.error.requestId === undefined ? {} : { requestId: envelope.data.error.requestId })
        });
      }
      if (response.status === 401 || response.status === 403) {
        throw new BurpMcpError(
          "BRIDGE_AUTH_FAILED",
          "The Burp bridge rejected the session token. Re-enable the bridge to refresh discovery.",
          { status: response.status }
        );
      }
      throw new BurpMcpError("BRIDGE_HTTP_ERROR", `The Burp bridge returned HTTP ${response.status}.`, {
        status: response.status
      });
    }

    const parsed = options.schema.safeParse(json);
    if (!parsed.success) {
      throw new BurpMcpError(
        "BRIDGE_INVALID_RESPONSE",
        "The Burp bridge returned data that does not match the v1 contract.",
        { cause: parsed.error }
      );
    }

    if (totalDecodedRawBytes(parsed.data) > MAX_RAW_BYTES) {
      throw new BurpMcpError(
        "PAYLOAD_TOO_LARGE",
        `The total decoded raw output exceeded the ${MAX_RAW_BYTES} byte limit.`
      );
    }
    return parsed.data;
  }
}
