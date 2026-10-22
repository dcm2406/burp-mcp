export type BurpMcpErrorCode =
  | "DISCOVERY_NOT_FOUND"
  | "DISCOVERY_UNREADABLE"
  | "DISCOVERY_INVALID"
  | "BRIDGE_UNAVAILABLE"
  | "BRIDGE_TIMEOUT"
  | "BRIDGE_AUTH_FAILED"
  | "BRIDGE_HTTP_ERROR"
  | "BRIDGE_INVALID_RESPONSE"
  | "PAYLOAD_TOO_LARGE"
  | "INVALID_BASE64";

export class BurpMcpError extends Error {
  readonly code: BurpMcpErrorCode | string;
  readonly requestId?: string;
  readonly status?: number;

  constructor(
    code: BurpMcpErrorCode | string,
    message: string,
    options: { cause?: unknown; requestId?: string; status?: number } = {}
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = "BurpMcpError";
    this.code = code;
    if (options.requestId !== undefined) this.requestId = options.requestId;
    if (options.status !== undefined) this.status = options.status;
  }
}

export interface SafeToolError {
  code: string;
  message: string;
  requestId?: string;
}

export function toSafeToolError(error: unknown): SafeToolError {
  if (error instanceof BurpMcpError) {
    return {
      code: error.code,
      message: error.message,
      ...(error.requestId === undefined ? {} : { requestId: error.requestId })
    };
  }

  return {
    code: "INTERNAL_ERROR",
    message: "The Burp MCP server encountered an unexpected error. Check stderr for diagnostics."
  };
}
