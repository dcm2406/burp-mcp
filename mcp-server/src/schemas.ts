import * as z from "zod/v4";

import { MAX_BASE64_CHARS, MAX_RAW_BYTES } from "./constants.js";

function base64Value(code: number): number {
  if (code >= 65 && code <= 90) return code - 65;
  if (code >= 97 && code <= 122) return code - 97 + 26;
  if (code >= 48 && code <= 57) return code - 48 + 52;
  if (code === 43) return 62;
  if (code === 47) return 63;
  return -1;
}

export function decodedBase64Size(value: string): number | undefined {
  if (value.length === 0) return 0;
  if (value.length % 4 !== 0) return undefined;
  const padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
  const dataLength = value.length - padding;
  for (let index = 0; index < dataLength; index += 1) {
    if (base64Value(value.charCodeAt(index)) < 0) return undefined;
  }
  for (let index = dataLength; index < value.length; index += 1) {
    if (value.charCodeAt(index) !== 61) return undefined;
  }
  if (padding === 2 && (base64Value(value.charCodeAt(dataLength - 1)) & 0x0f) !== 0) return undefined;
  if (padding === 1 && (base64Value(value.charCodeAt(dataLength - 1)) & 0x03) !== 0) return undefined;
  return (value.length / 4) * 3 - padding;
}

const CanonicalBase64DataSchema = z
  .string()
  .max(MAX_BASE64_CHARS, `Base64 data exceeds the ${MAX_RAW_BYTES} byte decoded limit`)
  .superRefine((value, context) => {
    const size = decodedBase64Size(value);
    if (size === undefined) {
      context.addIssue({ code: "custom", message: "data must be canonical standard Base64" });
    } else if (size > MAX_RAW_BYTES) {
      context.addIssue({ code: "custom", message: `decoded data exceeds ${MAX_RAW_BYTES} bytes` });
    }
  })
  .describe(`Canonical standard Base64; decoded payload must be at most ${MAX_RAW_BYTES} bytes`);

export const Base64DataSchema = CanonicalBase64DataSchema.min(4, "Base64 data must not be empty");

export const RawInputSchema = z
  .object({
    encoding: z.literal("base64").describe("Binary-safe encoding; always base64"),
    data: Base64DataSchema
  })
  .strict();

export const RawMessageSchema = z
  .object({
    encoding: z.literal("base64"),
    data: CanonicalBase64DataSchema,
    byteLength: z.number().int().nonnegative(),
    returnedByteLength: z.number().int().nonnegative(),
    truncated: z.boolean(),
    sha256: z.string().regex(/^[a-f0-9]{64}$/)
  })
  .strict()
  .refine((value) => decodedBase64Size(value.data) === value.returnedByteLength, {
    message: "returnedByteLength must match decoded Base64 data",
    path: ["returnedByteLength"]
  })
  .refine((value) => value.returnedByteLength <= value.byteLength, {
    message: "returnedByteLength must not exceed byteLength",
    path: ["returnedByteLength"]
  });

export const ServiceSchema = z
  .object({
    host: z.string().trim().min(1).max(253).describe("Destination hostname or IP address"),
    port: z.number().int().min(1).max(65_535).describe("Destination TCP port"),
    secure: z.boolean().describe("Use TLS for the destination connection")
  })
  .strict();

export const EmptyInputSchema = z.object({}).strict();

export const ProxyHistoryInputSchema = z
  .object({
    host: z.string().trim().min(1).max(253).optional().describe("Exact or bridge-supported host filter"),
    method: z
      .string()
      .trim()
      .min(1)
      .max(32)
      .regex(/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/, "method must be a valid HTTP token")
      .transform((value) => value.toUpperCase())
      .optional()
      .describe("HTTP method filter, for example GET or POST"),
    statusCodes: z
      .array(z.number().int().min(100).max(599))
      .max(20)
      .refine((values) => new Set(values).size === values.length, "statusCodes must not contain duplicates")
      .optional()
      .describe("Response status codes to include"),
    hasResponse: z.boolean().optional().describe("Filter by whether Burp captured a response"),
    search: z.string().max(256).optional().describe("Bridge-supported text search, at most 256 characters"),
    from: z.iso.datetime({ offset: true }).optional().describe("Inclusive ISO 8601 lower time bound"),
    to: z.iso.datetime({ offset: true }).optional().describe("Inclusive ISO 8601 upper time bound"),
    cursor: z.string().min(1).max(2048).optional().describe("Opaque cursor returned by the previous page"),
    limit: z.number().int().min(1).max(200).default(50).describe("Page size; default 50, maximum 200")
  })
  .strict()
  .refine(
    ({ from, to }) => from === undefined || to === undefined || Date.parse(from) <= Date.parse(to),
    { message: "from must not be later than to", path: ["from"] }
  );

export const GetProxyMessageInputSchema = z
  .object({
    ref: z.string().min(1).max(4096).describe("Opaque session-bound reference from burp_proxy_history")
  })
  .strict();

export const SendHttpRequestInputSchema = z
  .object({
    service: ServiceSchema,
    request: RawInputSchema
  })
  .strict();

export const SendToRepeaterInputSchema = z
  .object({
    service: ServiceSchema,
    request: RawInputSchema,
    tabName: z.string().trim().min(1).max(80).optional().describe("Optional Burp Repeater tab name")
  })
  .strict();

export const SendHttpRequestBatchInputSchema = z
  .object({
    items: z
      .array(z.object({ service: ServiceSchema, request: RawInputSchema }).strict())
      .min(1)
      .max(50)
      .describe("Items to send; at most 50 per call."),
    concurrency: z.coerce
      .number()
      .int()
      .min(1)
      .max(10)
      .optional()
      .describe("Upper bound on requests in flight; 1..10, default 4."),
    responseMode: z
      .enum(["summary", "full"])
      .optional()
      .describe("summary (default) returns metadata only; full also returns Base64 bodies within a batch budget.")
  })
  .strict();

export const BatchBinaryPartSchema = z
  .object({
    omitted: z.boolean().optional(),
    data: z.string().optional(),
    byteLength: z.number().int().nonnegative(),
    sha256: z.string().regex(/^[a-f0-9]{64}$/)
  })
  .strict();

export const BatchSendItemResultSchema = z
  .object({
    statusCode: z.number().int().nullable(),
    request: BatchBinaryPartSchema,
    response: BatchBinaryPartSchema.nullable(),
    hasResponse: z.boolean(),
    truncated: z.boolean(),
    omitted: z.boolean(),
    durationMs: z.number().int().nonnegative(),
    error: z.object({ code: z.string(), requestId: z.string() }).strict().optional()
  })
  .strict();

export const SendHttpRequestBatchDataSchema = z
  .object({
    results: z.array(BatchSendItemResultSchema),
    succeeded: z.number().int().nonnegative(),
    failed: z.number().int().nonnegative()
  })
  .strict();

export const CollaboratorPayloadInputSchema = z
  .object({
    customData: z
      .string()
      .regex(/^[A-Za-z0-9]{1,16}$/)
      .optional()
      .describe("Optional tag echoed back on every interaction; identifies the injection point. Burp accepts at most 16 letters or digits."),
    includeServerLocation: z
      .coerce
      .boolean()
      .optional()
      .describe("Append the Collaborator server hostname to the payload (default true).")
  })
  .strict();

export const CollaboratorInteractionsInputSchema = z
  .object({
    ref: z
      .string()
      .min(1)
      .max(1024)
      .optional()
      .describe("Opaque session-bound reference returned by burp_collaborator_payload."),
    payload: z
      .string()
      .min(1)
      .max(253)
      .optional()
      .describe("The raw payload string itself, instead of its ref."),
    mode: z
      .enum(["summary", "full"])
      .optional()
      .describe("full (default) includes Base64 bodies of the OOB hit; summary returns metadata only.")
  })
  .strict()
  .refine((value) => value.ref === undefined || value.payload === undefined, {
    message: "Provide either 'ref' or 'payload', not both"
  });

export const CollaboratorPayloadDataSchema = z
  .object({
    ref: z.string(),
    payloadId: z.string(),
    payload: z.string(),
    server: z.string().optional(),
    customData: z.string().optional()
  })
  .strict();

export const CollaboratorInteractionSchema = z
  .object({
    ref: z.string(),
    type: z.enum(["DNS", "HTTP", "SMTP"]),
    time: z.iso.datetime({ offset: true }),
    clientIp: z.string(),
    clientPort: z.number().int().nonnegative(),
    customData: z.string().optional(),
    dns: z
      .object({
        queryType: z.string(),
        domain: z.string().optional(),
        rawHex: z.string().optional()
      })
      .strict()
      .optional(),
    http: z
      .object({
        method: z.string(),
        path: z.string(),
        request: z.object({ data: z.string().optional(), byteLength: z.number().int().nonnegative(), sha256: z.string() }).strict(),
        response: z
          .object({ data: z.string().optional(), byteLength: z.number().int().nonnegative(), sha256: z.string() })
          .strict()
          .nullable()
      })
      .strict()
      .optional(),
    smtp: z.object({ protocol: z.string() }).strict().optional()
  })
  .strict();

export const CollaboratorInteractionsDataSchema = z
  .object({
    interactions: z.array(CollaboratorInteractionSchema),
    total: z.number().int().nonnegative(),
    newCount: z.number().int().nonnegative()
  })
  .strict();

export const StatusDataSchema = z
  .object({
    connected: z.literal(true),
    sessionId: z.string().min(1),
    extensionVersion: z.string().min(1),
    burpVersion: z.string().min(1),
    startedAt: z.iso.datetime({ offset: true }),
    capabilities: z.array(
      z.enum(["proxyHistory", "sendHttpRequest", "sendToRepeater", "sendHttpRequestBatch", "collaborator"])
    ),
    limits: z
      .object({
        maxRawBytes: z.literal(MAX_RAW_BYTES),
        maxPageSize: z.literal(200),
        maxBatchItems: z.literal(50),
        maxBatchConcurrency: z.literal(10),
        maxBatchInputBytes: z.literal(8 * 1024 * 1024),
        maxBatchOutputBytes: z.literal(8 * 1024 * 1024)
      })
      .strict()
  })
  .strict();

export const HistoryItemSchema = z
  .object({
    ref: z.string(),
    burpHistoryId: z.number().int(),
    time: z.iso.datetime({ offset: true }),
    method: z.string(),
    url: z.string(),
    service: ServiceSchema,
    statusCode: z.number().int().nullable(),
    mimeType: z.string().nullable(),
    hasResponse: z.boolean(),
    edited: z.boolean(),
    listenerPort: z.number().int().min(1).max(65_535),
    requestByteLength: z.number().int().nonnegative(),
    responseByteLength: z.number().int().nonnegative()
  })
  .strict();

export const ProxyHistoryDataSchema = z
  .object({
    items: z.array(HistoryItemSchema),
    nextCursor: z.string().nullable().optional(),
    returned: z.number().int().nonnegative(),
    malformedItemCount: z.number().int().nonnegative()
  })
  .strict();

export const ProxyMessageDataSchema = z
  .object({
    ref: z.string(),
    burpHistoryId: z.number().int(),
    time: z.iso.datetime({ offset: true }),
    method: z.string(),
    url: z.string(),
    service: ServiceSchema,
    statusCode: z.number().int().nullable(),
    request: RawMessageSchema,
    response: RawMessageSchema.nullable(),
    hasResponse: z.boolean()
  })
  .strict();

export const SendHttpResponseDataSchema = z
  .object({
    service: ServiceSchema,
    request: RawMessageSchema,
    response: RawMessageSchema.nullable(),
    hasResponse: z.boolean(),
    statusCode: z.number().int().nullable()
  })
  .strict();

export const RepeaterDataSchema = z
  .object({
    accepted: z.literal(true),
    tabName: z.string().optional()
  })
  .strict();

export function successOutputSchema<T extends z.ZodType>(dataSchema: T) {
  return z.object({ ok: z.literal(true), data: dataSchema }).strict();
}

export type RawMessage = z.infer<typeof RawMessageSchema>;
export type RawInput = z.infer<typeof RawInputSchema>;
export type Service = z.infer<typeof ServiceSchema>;
export type ProxyHistoryInput = z.infer<typeof ProxyHistoryInputSchema>;
export type GetProxyMessageInput = z.infer<typeof GetProxyMessageInputSchema>;
export type SendHttpRequestInput = z.infer<typeof SendHttpRequestInputSchema>;
export type SendHttpRequestBatchInput = z.infer<typeof SendHttpRequestBatchInputSchema>;
export type SendHttpRequestBatchData = z.infer<typeof SendHttpRequestBatchDataSchema>;
export type CollaboratorPayloadInput = z.infer<typeof CollaboratorPayloadInputSchema>;
export type CollaboratorPayloadData = z.infer<typeof CollaboratorPayloadDataSchema>;
export type CollaboratorInteractionsInput = z.infer<typeof CollaboratorInteractionsInputSchema>;
export type CollaboratorInteractionsData = z.infer<typeof CollaboratorInteractionsDataSchema>;
export type SendToRepeaterInput = z.infer<typeof SendToRepeaterInputSchema>;
export type StatusData = z.infer<typeof StatusDataSchema>;
export type ProxyHistoryData = z.infer<typeof ProxyHistoryDataSchema>;
export type ProxyMessageData = z.infer<typeof ProxyMessageDataSchema>;
export type SendHttpResponseData = z.infer<typeof SendHttpResponseDataSchema>;
export type RepeaterData = z.infer<typeof RepeaterDataSchema>;
