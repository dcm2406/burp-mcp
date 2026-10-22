export const PACKAGE_NAME = "burp-mcp-server";
export const PACKAGE_VERSION = "0.1.0";

export const DEFAULT_BRIDGE_TIMEOUT_MS = 30_000;
export const MAX_RAW_BYTES = 10 * 1024 * 1024;
export const MAX_BASE64_CHARS = Math.ceil(MAX_RAW_BYTES / 3) * 4;
export const MAX_BRIDGE_JSON_BYTES = 16 * 1024 * 1024;
export const MAX_STDIO_BUFFER_BYTES = 17 * 1024 * 1024;
export const MAX_TOOL_INPUT_ELEMENTS = 10_000;

export const SESSION_FILE_ENV = "BURP_MCP_SESSION_FILE";
// Segment names are joined with the platform separator at every call site.
// Embedding a separator in the constant (e.g. "burp-mcp\\active-session.json")
// resolves correctly on Windows but produces a literal backslash filename on
// POSIX systems, so both sides of the bridge must build the path from segments.
export const SESSION_DIR_NAME = "burp-mcp";
export const SESSION_FILE_NAME = "active-session.json";
