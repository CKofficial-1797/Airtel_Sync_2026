import { describe, expect, it } from "vitest";
import type { CheckResult, DocumentView, KycView } from "../api/types";
import k03Json from "../test/fixtures/kyc-k03.json";
import k11Json from "../test/fixtures/kyc-k11.json";
import { DOC_TYPES, pickText, readSummary, slotsForCheck, stepFor, unreadSlots, withDocument, withSubmitResult } from "./kyc";

const k03 = k03Json as unknown as KycView;
const k11 = k11Json as unknown as KycView;
const check = (code: string, evidence: Record<string, unknown> | null = null) => ({ code, evidence }) as CheckResult;

describe("stepFor (resume from server state)", () => {
  it("consent without an active consent, whatever the status", () => {
    expect(stepFor({ ...k03, consent: { ...k03.consent, active: false }, kycStatus: "KYC_PENDING_CONSENT" })).toBe("consent");
    expect(stepFor({ ...k03, consent: { ...k03.consent, active: false } })).toBe("consent");
  });

  it("upload while in progress, results after a verdict", () => {
    expect(stepFor({ ...k03, kycStatus: "KYC_IN_PROGRESS" })).toBe("upload");
    expect(stepFor(k03)).toBe("results");
    expect(stepFor(k11)).toBe("results");
    expect(stepFor({ ...k03, kycStatus: "KYC_REJECTED" })).toBe("results");
  });
});

describe("slotsForCheck", () => {
  it("maps each check to the document that clears it", () => {
    expect(slotsForCheck(check("C14"))).toEqual(["ADDRESS_PROOF"]);
    expect(slotsForCheck(check("C15"))).toEqual(["SIGNATORY_ID"]);
    expect(slotsForCheck(check("C08"))).toEqual(["UDYAM_CERTIFICATE"]);
    expect(slotsForCheck(check("C04"))).toEqual(["GST_CERTIFICATE"]);
  });

  it("reads the affected slots from the evidence for C01, C02 and C03", () => {
    expect(slotsForCheck(check("C01", { missing: ["UDYAM_CERTIFICATE"] }))).toEqual(["UDYAM_CERTIFICATE"]);
    expect(slotsForCheck(check("C02", { unreadable: ["GST_CERTIFICATE"] }))).toEqual(["GST_CERTIFICATE"]);
    expect(slotsForCheck(check("C03", { detected: { SIGNATORY_ID: "ADDRESS_PROOF" } }))).toEqual(["SIGNATORY_ID"]);
  });

  it("maps name sources for C10 without repeating a slot", () => {
    expect(slotsForCheck(check("C10", { mismatch: ["gstTradeName", "gstLegalName"] }))).toEqual(["GST_CERTIFICATE"]);
    expect(slotsForCheck(check("C10", { mismatch: ["billBilledTo", "idForBusiness"] }))).toEqual(["ADDRESS_PROOF", "SIGNATORY_ID"]);
  });

  it("has no slot for the order check and survives null evidence", () => {
    expect(slotsForCheck(check("C17"))).toEqual([]);
    expect(slotsForCheck(check("C01"))).toEqual([]);
  });
});

describe("state updates", () => {
  const newBill = { ...k11.documents.find((d) => d.docType === "ADDRESS_PROOF")!, status: "OK" } as DocumentView;

  it("a new document after a verdict reopens the KYC and replaces that slot", () => {
    const next = withDocument(k11, newBill);
    expect(next.kycStatus).toBe("KYC_IN_PROGRESS");
    expect(next.orderStatus).toBe("KYC_IN_PROGRESS");
    expect(next.documents.filter((d) => d.docType === "ADDRESS_PROOF")).toHaveLength(1);
    expect(next.documents).toHaveLength(4);
    expect(next.overall).toBe("ACTION_REQUIRED"); // the last check stays visible until "Check again"
  });

  it("a document while in progress keeps the status and clears it from missing", () => {
    const start: KycView = { ...k11, kycStatus: "KYC_IN_PROGRESS", documents: [], missingDocuments: [...DOC_TYPES] };
    const next = withDocument(start, newBill);
    expect(next.kycStatus).toBe("KYC_IN_PROGRESS");
    expect(next.missingDocuments).not.toContain("ADDRESS_PROOF");
    expect(unreadSlots(next)).toEqual(["GST_CERTIFICATE", "UDYAM_CERTIFICATE", "SIGNATORY_ID"]);
  });

  it("a submit result moves the view to the verdict", () => {
    const next = withSubmitResult({ ...k11, overall: null, checks: [], kycStatus: "KYC_IN_PROGRESS" }, {
      orderId: k11.orderId,
      orderRef: k11.orderRef,
      overall: "VERIFIED",
      orderStatus: "KYC_VERIFIED",
      blockingChecks: [],
      warnings: [],
      checks: k03.checks,
      estimatedActivationDate: "2026-07-20",
      runId: "r",
    });
    expect(stepFor(next)).toBe("results");
    expect(next.overall).toBe("VERIFIED");
    expect(next.estimatedActivationDate).toBe("2026-07-20");
  });

  it("unreadSlots counts unreadable and wrong documents as not read", () => {
    expect(unreadSlots(k03)).toEqual([]);
    const docs = k03.documents.map((d) => (d.docType === "SIGNATORY_ID" ? { ...d, status: "WRONG_DOC_TYPE" as const } : d));
    expect(unreadSlots({ ...k03, documents: docs })).toEqual(["SIGNATORY_ID"]);
  });
});

describe("readSummary and pickText", () => {
  it("summarises each document type with server-masked values", () => {
    const of = (t: string) => readSummary(k03.documents.find((d) => d.docType === t)!);
    expect(of("GST_CERTIFICATE")).toEqual({ idLabel: "gstin", id: "27XUI*****1ZP", name: "Ganesh Woodcraft" });
    expect(of("UDYAM_CERTIFICATE")).toEqual({ idLabel: "udyam", id: "UDYAM-MH-00-****696", name: "Ganesh Woodcraft" });
    expect(of("SIGNATORY_ID")?.id).toMatch(/^\*+889$/);
    expect(of("ADDRESS_PROOF")?.id).toMatch(/^\*+928$/);
  });

  it("has no summary for documents that were not read", () => {
    const doc = { ...k03.documents[0]!, status: "UNREADABLE" as const, fields: null };
    expect(readSummary(doc)).toBeNull();
  });

  it("picks the language", () => {
    expect(pickText("hi", "a", "b")).toBe("b");
    expect(pickText("en", "a", "b")).toBe("a");
    expect(pickText<string | null>("hi", null, null)).toBeNull();
  });
});
