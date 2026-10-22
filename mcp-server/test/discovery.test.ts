import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { homedir, tmpdir } from "node:os";
import { basename, join, sep } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import { SESSION_DIR_NAME, SESSION_FILE_ENV, SESSION_FILE_NAME } from "../src/constants.js";
import { discoverBridge, resolveSessionFile } from "../src/discovery.js";
import { BurpMcpError } from "../src/errors.js";

const temporaryDirectories: string[] = [];

async function tempDirectory(): Promise<string> {
  const directory = await mkdtemp(join(tmpdir(), "burp-mcp-discovery-"));
  temporaryDirectories.push(directory);
  return directory;
}

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true, force: true })));
});

describe("session discovery", () => {
  it("uses BURP_MCP_SESSION_FILE before LOCALAPPDATA", () => {
    expect(
      resolveSessionFile({ env: { [SESSION_FILE_ENV]: "D:\\isolated\\session.json", LOCALAPPDATA: "D:\\local" } })
    ).toBe("D:\\isolated\\session.json");
  });

  it("joins the burp-mcp segments under LOCALAPPDATA", () => {
    expect(resolveSessionFile({ env: { LOCALAPPDATA: "D:\\local" } })).toBe(
      join("D:\\local", SESSION_DIR_NAME, SESSION_FILE_NAME)
    );
  });

  it("uses XDG_DATA_HOME when LOCALAPPDATA is absent", () => {
    expect(resolveSessionFile({ env: { XDG_DATA_HOME: "/data/xdg" } })).toBe(
      join("/data/xdg", SESSION_DIR_NAME, SESSION_FILE_NAME)
    );
  });

  it("defaults to the user local share when neither variable is present", () => {
    expect(resolveSessionFile({ env: {} })).toBe(
      join(homedir(), ".local", "share", SESSION_DIR_NAME, SESSION_FILE_NAME)
    );
  });

  it("keeps the descriptor filename as a separate path segment", () => {
    // Regression: a hardcoded "burp-mcp\\active-session.json" constant resolved to a
    // literal backslash filename on POSIX systems and broke discovery without the override.
    for (const env of [{}, { XDG_DATA_HOME: "/data/xdg" }, { LOCALAPPDATA: "D:\\local" }]) {
      const resolved = resolveSessionFile({ env });
      const expectedTail = join(SESSION_DIR_NAME, SESSION_FILE_NAME);
      expect(resolved.endsWith(expectedTail)).toBe(true);
      expect(resolved.includes(sep + SESSION_FILE_NAME)).toBe(true);
      expect(basename(resolved)).toBe(SESSION_FILE_NAME);
    }
  });

  it("prefers an explicit session file over every environment fallback", () => {
    expect(resolveSessionFile({ sessionFile: "D:\\isolated\\explicit.json", env: { LOCALAPPDATA: "D:\\local" } })).toBe(
      "D:\\isolated\\explicit.json"
    );
  });

  it("loads a valid current-session descriptor", async () => {
    const directory = await tempDirectory();
    const sessionFile = join(directory, "session.json");
    await writeFile(
      sessionFile,
      JSON.stringify({
        schemaVersion: 1,
        sessionId: "session-1",
        baseUrl: "http://127.0.0.1:49152/",
        token: "0123456789abcdef0123456789abcdef"
      })
    );

    await expect(discoverBridge({ sessionFile })).resolves.toEqual({
      sessionId: "session-1",
      baseUrl: "http://127.0.0.1:49152",
      token: "0123456789abcdef0123456789abcdef"
    });
  });

  it.each([
    "http://localhost:49152",
    "http://[::1]:49152",
    "https://127.0.0.1:49152",
    "http://127.0.0.1:49152/v1",
    "http://user:password@127.0.0.1:49152"
  ])("rejects unsafe bridge URL %s", async (baseUrl) => {
    const directory = await tempDirectory();
    const sessionFile = join(directory, "session.json");
    await writeFile(
      sessionFile,
      JSON.stringify({ schemaVersion: 1, sessionId: "session-1", baseUrl, token: "0123456789abcdef" })
    );

    await expect(discoverBridge({ sessionFile })).rejects.toMatchObject({ code: "DISCOVERY_INVALID" });
  });

  it("maps a missing descriptor without exposing its path", async () => {
    const error = await discoverBridge({ sessionFile: "Z:\\secret\\missing.json" }).catch(
      (caught: unknown) => caught
    );
    expect(error).toBeInstanceOf(BurpMcpError);
    expect(error).toMatchObject({ code: "DISCOVERY_NOT_FOUND" });
    expect((error as Error).message).not.toContain("Z:\\secret");
  });
});
