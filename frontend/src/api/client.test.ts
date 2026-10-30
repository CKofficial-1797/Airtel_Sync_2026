import { afterEach, describe, expect, it, vi } from "vitest";
import { kiranaQuote } from "../test/fixtures";
import { AiUnavailableError, ApiError, NetworkError, QuoteChangedError, request } from "./client";

function mockFetch(status: number, body: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } })),
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("request()", () => {
  it("returns the JSON body on success", async () => {
    mockFetch(200, { sessionId: "s1", greeting: "hi" });
    await expect(request("/api/advisor/sessions", { method: "POST", body: {} })).resolves.toEqual({
      sessionId: "s1",
      greeting: "hi",
    });
  });

  it("maps 503 AI_UNAVAILABLE to AiUnavailableError", async () => {
    mockFetch(503, { status: 503, error: "Service Unavailable", code: "AI_UNAVAILABLE", message: "busy", path: "/x" });
    const err = await request("/x").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(AiUnavailableError);
    expect((err as AiUnavailableError).code).toBe("AI_UNAVAILABLE");
  });

  it("maps 409 QUOTE_CHANGED to QuoteChangedError with the fresh quote", async () => {
    const quote = kiranaQuote({ monthlyTotalInr: 899 });
    mockFetch(409, { status: 409, error: "Conflict", code: "QUOTE_CHANGED", message: "changed", path: "/api/orders", quote });
    const err = await request("/api/orders", { method: "POST", body: {} }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(QuoteChangedError);
    expect((err as QuoteChangedError).quote.monthlyTotalInr).toBe(899);
  });

  it("keeps field errors for 400s", async () => {
    mockFetch(400, { status: 400, error: "Bad Request", code: "INVALID_REQUEST", message: "invalid", path: "/x", errors: ["text: must not be blank"] });
    const err = (await request("/x").catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(400);
    expect(err.fieldErrors).toEqual(["text: must not be blank"]);
  });

  it("maps a failed connection to NetworkError", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => Promise.reject(new TypeError("Failed to fetch"))));
    await expect(request("/x")).rejects.toBeInstanceOf(NetworkError);
  });
});
