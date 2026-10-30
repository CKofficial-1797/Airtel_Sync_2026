import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, ConsentRequiredError, ExtractionUnavailableError } from "./client";
import { giveConsent, submitKyc, uploadDocument } from "./kyc";

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn(async (..._args: Parameters<typeof fetch>) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }),
  );
  vi.stubGlobal("fetch", fn);
  return fn;
}

const png = new File([new Uint8Array(4)], "gst.png", { type: "image/png" });

afterEach(() => vi.unstubAllGlobals());

describe("api/kyc", () => {
  it("maps 403 CONSENT_REQUIRED to a typed error", async () => {
    mockFetch(403, { status: 403, error: "Forbidden", code: "CONSENT_REQUIRED", message: "Give consent", path: "/x" });
    const err = await uploadDocument("o1", "GST_CERTIFICATE", png).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ConsentRequiredError);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ConsentRequiredError).status).toBe(403);

    const submitErr = await submitKyc("o1").catch((e: unknown) => e);
    expect(submitErr).toBeInstanceOf(ConsentRequiredError);
  });

  it("keeps other 403s as plain ApiError", async () => {
    mockFetch(403, { status: 403, error: "Forbidden", code: "OTHER", message: "no", path: "/x" });
    const err = await submitKyc("o1").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).not.toBeInstanceOf(ConsentRequiredError);
  });

  it("maps 503 EXTRACTION_UNAVAILABLE to a typed error", async () => {
    mockFetch(503, { status: 503, error: "Service Unavailable", code: "EXTRACTION_UNAVAILABLE", message: "busy", path: "/x" });
    await expect(uploadDocument("o1", "GST_CERTIFICATE", png)).rejects.toBeInstanceOf(ExtractionUnavailableError);
  });

  it("uploads as multipart: no JSON header, docType and file in the form", async () => {
    const fn = mockFetch(200, { docType: "GST_CERTIFICATE", status: "OK" });
    await uploadDocument("o1", "GST_CERTIFICATE", png);

    const [url, init] = fn.mock.calls[0]!;
    expect(url).toBe("/api/orders/o1/kyc/documents");
    expect(init?.method).toBe("POST");
    expect(init?.headers).toBeUndefined();
    const form = init?.body as FormData;
    expect(form).toBeInstanceOf(FormData);
    expect(form.get("docType")).toBe("GST_CERTIFICATE");
    expect((form.get("file") as File).name).toBe("gst.png");
  });

  it("posts consent as JSON", async () => {
    const fn = mockFetch(200, {});
    await giveConsent("o1", { noticeVersion: "v", language: "hi", purposes: { KYC_VERIFICATION: true } });

    const [url, init] = fn.mock.calls[0]!;
    expect(url).toBe("/api/orders/o1/kyc/consent");
    expect(init?.headers).toEqual({ "Content-Type": "application/json" });
    expect(JSON.parse(init?.body as string)).toEqual({ noticeVersion: "v", language: "hi", purposes: { KYC_VERIFICATION: true } });
  });
});
