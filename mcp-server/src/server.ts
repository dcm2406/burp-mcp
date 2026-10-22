import { McpServer } from "@modelcontextprotocol/server";

import { BridgeClient, type BridgeApi } from "./bridge-client.js";
import { MAX_TOOL_INPUT_ELEMENTS, PACKAGE_NAME, PACKAGE_VERSION } from "./constants.js";
import { registerBurpTools } from "./tools.js";

export function createBurpMcpServer(bridge: BridgeApi = new BridgeClient()): McpServer {
  const server = new McpServer(
    {
      name: PACKAGE_NAME,
      version: PACKAGE_VERSION,
      description: "Local MCP access to the active Burp Suite bridge"
    },
    { maxToolInputElements: MAX_TOOL_INPUT_ELEMENTS }
  );
  registerBurpTools(server, bridge);
  return server;
}
