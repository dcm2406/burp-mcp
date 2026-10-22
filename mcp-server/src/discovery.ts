import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

import * as z from "zod/v4";

import { SESSION_DIR_NAME, SESSION_FILE_ENV, SESSION_FILE_NAME } from "./constants.js";
import { BurpMcpError } from "./errors.js";

const DescriptorSchema = z
  .object({
    schemaVersion: z.literal(1),
    sessionId: z.string().min(1).max(1024),
    baseUrl: z.url(),
    token: z.string().min(16).max(4096)
  })
  .passthrough();

export interface BridgeSession {
  sessionId: string;
  baseUrl: string;
  token: string;
}

export interface DiscoveryOptions {
  sessionFile?: string;
  env?: NodeJS.ProcessEnv;
  localAppData?: string;
}

export function resolveSessionFile(options: DiscoveryOptions = {}): string {
  if (options.sessionFile !== undefined) return options.sessionFile;
  const env = options.env ?? process.env;
  const override = env[SESSION_FILE_ENV];
  if (override !== undefined && override.trim() !== "") return override;

  const localAppData = options.localAppData ?? env.LOCALAPPDATA;
  if (localAppData !== undefined && localAppData.trim() !== "") {
    return join(localAppData, SESSION_DIR_NAME, SESSION_FILE_NAME);
  }

  const xdgDataHome = env.XDG_DATA_HOME;
  const dataHome =
    xdgDataHome !== undefined && xdgDataHome.trim() !== ""
      ? xdgDataHome
      : join(homedir(), ".local", "share");
  return join(dataHome, SESSION_DIR_NAME, SESSION_FILE_NAME);
}

function validateLoopbackBaseUrl(value: string): string {
  const url = new URL(value);
  if (
    url.protocol !== "http:" ||
    url.hostname !== "127.0.0.1" ||
    url.port === "" ||
    url.username !== "" ||
    url.password !== "" ||
    url.search !== "" ||
    url.hash !== "" ||
    (url.pathname !== "" && url.pathname !== "/")
  ) {
    throw new BurpMcpError(
      "DISCOVERY_INVALID",
      "The active Burp session descriptor must use literal http://127.0.0.1:<port> with no credentials, query, or path."
    );
  }
  return url.origin;
}

export async function discoverBridge(options: DiscoveryOptions = {}): Promise<BridgeSession> {
  const sessionFile = resolveSessionFile(options);
  let text: string;
  try {
    text = await readFile(sessionFile, { encoding: "utf8" });
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === "ENOENT") {
      throw new BurpMcpError(
        "DISCOVERY_NOT_FOUND",
        "No active Burp MCP session was found. Enable the MCP bridge in Burp and try again.",
        { cause: error }
      );
    }
    throw new BurpMcpError(
      "DISCOVERY_UNREADABLE",
      "The active Burp MCP session descriptor could not be read. Check its current-user permissions.",
      { cause: error }
    );
  }

  let json: unknown;
  try {
    json = JSON.parse(text) as unknown;
  } catch (error) {
    throw new BurpMcpError("DISCOVERY_INVALID", "The active Burp MCP session descriptor is not valid JSON.", {
      cause: error
    });
  }

  const parsed = DescriptorSchema.safeParse(json);
  if (!parsed.success) {
    throw new BurpMcpError(
      "DISCOVERY_INVALID",
      "The active Burp MCP session descriptor has an unsupported or incomplete schema.",
      { cause: parsed.error }
    );
  }

  return {
    sessionId: parsed.data.sessionId,
    baseUrl: validateLoopbackBaseUrl(parsed.data.baseUrl),
    token: parsed.data.token
  };
}
