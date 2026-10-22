import type { McpServer } from "@modelcontextprotocol/server";

import type { BridgeApi } from "./bridge-client.js";
import { toSafeToolError } from "./errors.js";
import {
  EmptyInputSchema,
  CollaboratorInteractionsDataSchema,
  CollaboratorInteractionsInputSchema,
  CollaboratorPayloadDataSchema,
  CollaboratorPayloadInputSchema,
  GetProxyMessageInputSchema,
  ProxyHistoryDataSchema,
  ProxyHistoryInputSchema,
  ProxyMessageDataSchema,
  RepeaterDataSchema,
  SendHttpRequestBatchDataSchema,
  SendHttpRequestBatchInputSchema,
  SendHttpRequestInputSchema,
  SendHttpResponseDataSchema,
  SendToRepeaterInputSchema,
  StatusDataSchema,
  successOutputSchema,
  type RawMessage
} from "./schemas.js";

type StructuredData = Record<string, unknown>;

function rawSize(raw: RawMessage | null | undefined): number {
  if (raw === undefined || raw === null) return 0;
  const padding = raw.data.endsWith("==") ? 2 : raw.data.endsWith("=") ? 1 : 0;
  return (raw.data.length / 4) * 3 - padding;
}

function success(data: StructuredData, summary: string) {
  return {
    content: [{ type: "text" as const, text: summary }],
    structuredContent: { ok: true as const, data }
  };
}

function failure(error: unknown) {
  const safe = toSafeToolError(error);
  const requestSuffix = safe.requestId === undefined ? "" : ` Request ID: ${safe.requestId}.`;
  return {
    content: [{ type: "text" as const, text: `Burp bridge error [${safe.code}]: ${safe.message}${requestSuffix}` }],
    structuredContent: { ok: false as const, error: safe },
    isError: true as const
  };
}

export function registerBurpTools(server: McpServer, bridge: BridgeApi): void {
  server.registerTool(
    "burp_status",
    {
      title: "Burp MCP status",
      description:
        "Check active-session discovery and retrieve bridge/Burp version, capabilities, and limits. Does not expose the bearer token.",
      inputSchema: EmptyInputSchema,
      outputSchema: successOutputSchema(StatusDataSchema),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async () => {
      try {
        const data = await bridge.status();
        return success(data, `Burp MCP bridge is reachable (Burp version: ${data.burpVersion}).`);
      } catch (error) {
        return failure(error);
      }
    }
  );

  server.registerTool(
    "burp_proxy_history",
    {
      title: "Search Burp Proxy history",
      description:
        "Search and cursor-paginate Burp Proxy history by host, method, response status, response presence, text, and ISO 8601 time range. Returns opaque refs for burp_get_proxy_message; it does not return raw bytes.",
      inputSchema: ProxyHistoryInputSchema,
      outputSchema: successOutputSchema(ProxyHistoryDataSchema),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async (input) => {
      try {
        const data = await bridge.proxyHistory(input);
        const more = data.nextCursor == null ? "No next page." : "A next-page cursor is available.";
        return success(data, `Found ${data.items.length} Proxy history item(s). ${more}`);
      } catch (error) {
        return failure(error);
      }
    }
  );

  server.registerTool(
    "burp_get_proxy_message",
    {
      title: "Get a Burp Proxy message",
      description:
        "Retrieve one Proxy history request and optional response by its opaque session-bound ref. Raw messages are canonical Base64 and total decoded output is capped at 10 MiB.",
      inputSchema: GetProxyMessageInputSchema,
      outputSchema: successOutputSchema(ProxyMessageDataSchema),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false }
    },
    async (input) => {
      try {
        const data = await bridge.getProxyMessage(input);
        return success(
          data,
          `Retrieved Proxy message (${rawSize(data.request)} request bytes, ${rawSize(data.response)} response bytes).`
        );
      } catch (error) {
        return failure(error);
      }
    }
  );

  server.registerTool(
    "burp_send_http_request",
    {
      title: "Send an HTTP request through Burp",
      description:
        "Send exactly one Base64-encoded raw HTTP request through Burp to the supplied service and return the response. This has an external side effect and is never retried automatically. The decoded raw input/output limit is 10 MiB.",
      inputSchema: SendHttpRequestInputSchema,
      outputSchema: successOutputSchema(SendHttpResponseDataSchema),
      annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true }
    },
    async (input) => {
      try {
        const data = await bridge.sendHttpRequest(input);
        const status = data.statusCode === undefined ? "unknown" : String(data.statusCode);
        return success(data, `Burp sent one HTTP request; response status: ${status}. No retry was performed.`);
      } catch (error) {
        return failure(error);
      }
    }
  );

  server.registerTool(
    "burp_collaborator_payload",
    {
      title: "Generate a Burp Collaborator payload",
      description:
        "Generate a Burp Collaborator payload for out-of-band (blind) detection. Embed the payload string in the target request; any later DNS, HTTP or SMTP contact with it is proof that the target reached out. Tag it with customData (at most 16 letters or digits, for example 'sqli12') so interactions identify the injection point that fired. At most 20 payloads per bridge session.",
      inputSchema: CollaboratorPayloadInputSchema,
      outputSchema: successOutputSchema(CollaboratorPayloadDataSchema),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false }
    },
    async (input) => {
      try {
        const data = await bridge.collaboratorPayload(input);
        const tag = data.customData === undefined ? "" : ` tagged '${data.customData}'`;
        return success(data, `Collaborator payload${tag} ready. Poll burp_collaborator_interactions for OOB hits.`);
      } catch (error) {
        return failure(error);
      }
    }
  );

  server.registerTool(
    "burp_collaborator_interactions",
    {
      title: "Poll Burp Collaborator interactions",
      description:
        "Poll Burp Collaborator for out-of-band interactions, either for one payload (by the ref returned by burp_collaborator_payload, or by the raw payload string) or for the whole session. Burp hands each interaction over exactly once, so a poll only returns what arrived since the previous poll. Default mode is full: DNS/HTTP/SMTP detail including Base64 bodies of the hit - that detail is the evidence. Summary returns metadata only. Poll repeatedly (DNS arrives in seconds, HTTP/SMTP can take minutes) and only conclude 'no interaction' after confirming the outbound channel actually works.",
      inputSchema: CollaboratorInteractionsInputSchema,
      outputSchema: successOutputSchema(CollaboratorInteractionsDataSchema),
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: false, openWorldHint: false }
    },
    async (input) => {
      try {
        const data = await bridge.collaboratorInteractions(input);
        return success(data, `Collaborator reported ${data.interactions.length} interaction(s), ${data.newCount} new since the previous poll.`);
      } catch (error) {
        return failure(error);
      }
    }
  );

  server.registerTool(
    "burp_send_http_batch",
    {
      title: "Send several HTTP requests through Burp in one call",
      description:
        "Send between 1 and 50 Base64-encoded raw HTTP requests through Burp in a single call, with up to 10 in flight. Every item is sent exactly once and never retried. The batch is validated before anything is sent: a malformed item rejects the whole call and sends nothing. Results keep the order of items and report succeeded/failed counts; a failed item appears as an error entry without failing the batch. Default response mode is summary (metadata only); full mode also returns bodies within a 10 MiB per-item and 8 MiB per-batch budget.",
      inputSchema: SendHttpRequestBatchInputSchema,
      outputSchema: successOutputSchema(SendHttpRequestBatchDataSchema),
      annotations: {
        readOnlyHint: false,
        destructiveHint: true,
        idempotentHint: false,
        openWorldHint: true
      }
    },
    async (input) => {
      try {
        const data = await bridge.sendHttpRequestBatch(input);
        return success(
          data,
          `Burp sent ${data.succeeded + data.failed} request(s): ${data.succeeded} succeeded, ${data.failed} failed. No retry was performed.`
        );
      } catch (error) {
        return failure(error);
      }
    }
  );

  server.registerTool(
    "burp_send_to_repeater",
    {
      title: "Send a request to Burp Repeater",
      description:
        "Create a Burp Repeater tab containing one Base64-encoded raw request. This writes Burp UI state but does not send the HTTP request and is never retried automatically.",
      inputSchema: SendToRepeaterInputSchema,
      outputSchema: successOutputSchema(RepeaterDataSchema),
      annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false }
    },
    async (input) => {
      try {
        const data = await bridge.sendToRepeater(input);
        const name = data.tabName === undefined ? "" : ` '${data.tabName}'`;
        return success(data, `Burp Repeater tab${name} was created without sending the request.`);
      } catch (error) {
        return failure(error);
      }
    }
  );
}
