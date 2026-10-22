import { describe, expect, it } from "vitest";

import { MAX_RAW_BYTES } from "../src/constants.js";
import {
  CollaboratorInteractionsInputSchema,
  CollaboratorPayloadInputSchema,
  decodedBase64Size,
  ProxyHistoryInputSchema,
  RawInputSchema,
  SendHttpRequestBatchInputSchema
} from "../src/schemas.js";

describe("tool schemas", () => {
  it("computes decoded Base64 sizes without decoding", () => {
    expect(decodedBase64Size("SGVsbG8=")).toBe(5);
    expect(decodedBase64Size("not base64")).toBeUndefined();
  });

  it("accepts exactly 10 MiB and rejects one byte more", () => {
    const maximum = Buffer.alloc(MAX_RAW_BYTES).toString("base64");
    const tooLarge = Buffer.alloc(MAX_RAW_BYTES + 1).toString("base64");
    expect(RawInputSchema.safeParse({ encoding: "base64", data: maximum }).success).toBe(true);
    expect(RawInputSchema.safeParse({ encoding: "base64", data: tooLarge }).success).toBe(false);
  });

  it("accepts a batch of 1..50 requests and rejects 0 or 51", () => {
    const item = {
      service: { host: "127.0.0.1", port: 8888, secure: false },
      request: { encoding: "base64", data: "R0VUIC8gc21va2UgSFRUUC8xLjENCkhvc3Q6IDEyNy4wLjAuMTo4ODg4DQoNCg==" }
    };
    expect(SendHttpRequestBatchInputSchema.safeParse({ items: [item] }).success).toBe(true);
    expect(SendHttpRequestBatchInputSchema.safeParse({ items: Array(50).fill(item) }).success).toBe(true);
    expect(SendHttpRequestBatchInputSchema.safeParse({ items: [] }).success).toBe(false);
    expect(SendHttpRequestBatchInputSchema.safeParse({ items: Array(51).fill(item) }).success).toBe(false);
  });

  it("bounds concurrency to 1..10 and rejects anything else", () => {
    const base = { items: [{ service: { host: "h", port: 80, secure: false }, request: { encoding: "base64", data: "R0VU" } }] };
    for (const value of [1, 10]) {
      expect(SendHttpRequestBatchInputSchema.safeParse({ ...base, concurrency: value }).success).toBe(true);
    }
    for (const value of [0, 11, -1]) {
      expect(SendHttpRequestBatchInputSchema.safeParse({ ...base, concurrency: value }).success).toBe(false);
    }
  });

  it("accepts only summary or full response modes", () => {
    const base = { items: [{ service: { host: "h", port: 80, secure: false }, request: { encoding: "base64", data: "R0VU" } }] };
    expect(SendHttpRequestBatchInputSchema.safeParse({ ...base, responseMode: "summary" }).success).toBe(true);
    expect(SendHttpRequestBatchInputSchema.safeParse({ ...base, responseMode: "full" }).success).toBe(true);
    expect(SendHttpRequestBatchInputSchema.safeParse({ ...base, responseMode: "bodies" }).success).toBe(false);
  });

  it("rejects non-canonical base64 inside batch items", () => {
    const base = { items: [{ service: { host: "h", port: 80, secure: false }, request: { encoding: "base64", data: "R0VU=" } }] };
    expect(SendHttpRequestBatchInputSchema.safeParse(base).success).toBe(false);
  });

  it("validates Collaborator customData tags and rejects unsafe ones", () => {
    for (const tag of ["a", "sqli12", "abc123DEF456ghi7"]) {
      expect(CollaboratorPayloadInputSchema.safeParse({ customData: tag }).success).toBe(true);
    }
    for (const tag of ["a-b", "a_b", "a.b", "sqli:login", "x".repeat(17), ""]) {
      expect(CollaboratorPayloadInputSchema.safeParse({ customData: tag }).success).toBe(false);
    }
  });

  it("accepts ref xor payload for interaction polls and rejects both", () => {
    expect(CollaboratorInteractionsInputSchema.safeParse({ ref: "abc" }).success).toBe(true);
    expect(CollaboratorInteractionsInputSchema.safeParse({ payload: "abc.burpcollaborator.net" }).success).toBe(true);
    expect(CollaboratorInteractionsInputSchema.safeParse({}).success).toBe(true);
    expect(CollaboratorInteractionsInputSchema.safeParse({ ref: "abc", payload: "abc.burpcollaborator.net" }).success).toBe(false);
  });

  it("accepts only summary or full interaction modes", () => {
    expect(CollaboratorInteractionsInputSchema.safeParse({ mode: "summary" }).success).toBe(true);
    expect(CollaboratorInteractionsInputSchema.safeParse({ mode: "full" }).success).toBe(true);
    expect(CollaboratorInteractionsInputSchema.safeParse({ mode: "raw" }).success).toBe(false);
  });

  it("normalizes methods, applies pagination default, and validates time order", () => {
    expect(ProxyHistoryInputSchema.parse({ method: "post" })).toMatchObject({ method: "POST", limit: 50 });
    expect(
      ProxyHistoryInputSchema.safeParse({ from: "2026-10-20T12:00:00Z", to: "2026-10-20T11:00:00Z" }).success
    ).toBe(false);
  });
});
