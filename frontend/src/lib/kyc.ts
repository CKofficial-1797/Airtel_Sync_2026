import type { CheckResult, DocType, DocumentView, KycSubmitResult, KycView } from "../api/types";

export const DOC_TYPES: DocType[] = ["GST_CERTIFICATE", "UDYAM_CERTIFICATE", "SIGNATORY_ID", "ADDRESS_PROOF"];

export type KycStep = "consent" | "upload" | "results";

const VERDICT_STATUSES = ["KYC_VERIFIED", "KYC_ACTION_REQUIRED", "KYC_REJECTED"];

/** Which screen an order belongs on; the page is resumable because this only reads server state. */
export function stepFor(view: KycView): KycStep {
  if (!view.consent.active) {
    return "consent";
  }
  return VERDICT_STATUSES.includes(view.kycStatus) ? "results" : "upload";
}

/** A new document after a verdict reopens the KYC, as the server does. */
export function withDocument(view: KycView, doc: DocumentView): KycView {
  const reopened = VERDICT_STATUSES.includes(view.kycStatus);
  return {
    ...view,
    documents: [...view.documents.filter((d) => d.docType !== doc.docType), doc],
    missingDocuments: view.missingDocuments.filter((t) => t !== doc.docType),
    kycStatus: reopened ? "KYC_IN_PROGRESS" : view.kycStatus,
    orderStatus: reopened ? "KYC_IN_PROGRESS" : view.orderStatus,
    estimatedActivationDate: reopened ? null : view.estimatedActivationDate,
  };
}

export function withSubmitResult(view: KycView, r: KycSubmitResult): KycView {
  return {
    ...view,
    overall: r.overall,
    checks: r.checks,
    kycStatus: r.orderStatus,
    orderStatus: r.orderStatus,
    estimatedActivationDate: r.estimatedActivationDate,
  };
}

/** The slots that are not successfully read yet. */
export function unreadSlots(view: KycView): DocType[] {
  return DOC_TYPES.filter((t) => view.documents.find((d) => d.docType === t)?.status !== "OK");
}

/** The backend sends every message in both languages; show the one for the UI language. */
export function pickText<T extends string | null>(language: string, en: T, hi: T): T {
  return language === "hi" ? hi : en;
}

const SLOT_BY_CODE: Record<string, DocType> = {
  C04: "GST_CERTIFICATE",
  C05: "GST_CERTIFICATE",
  C06: "GST_CERTIFICATE",
  C07: "GST_CERTIFICATE",
  C13: "GST_CERTIFICATE",
  C08: "UDYAM_CERTIFICATE",
  C09: "UDYAM_CERTIFICATE",
  C16: "UDYAM_CERTIFICATE",
  C11: "SIGNATORY_ID",
  C15: "SIGNATORY_ID",
  C12: "ADDRESS_PROOF",
  C14: "ADDRESS_PROOF",
};

const SLOT_BY_NAME_SOURCE: Record<string, DocType> = {
  gstTradeName: "GST_CERTIFICATE",
  gstLegalName: "GST_CERTIFICATE",
  udyamEnterpriseName: "UDYAM_CERTIFICATE",
  billBilledTo: "ADDRESS_PROOF",
  idForBusiness: "SIGNATORY_ID",
};

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((v): v is string => typeof v === "string") : [];
}

/** The document slot(s) that clear a failed check. C17 is about the order, so it has none. */
export function slotsForCheck(check: CheckResult): DocType[] {
  const evidence = check.evidence ?? {};
  const known = (names: string[]) => names.filter((n): n is DocType => DOC_TYPES.includes(n as DocType));
  switch (check.code) {
    case "C01":
      return known(stringList(evidence.missing));
    case "C02":
      return known(stringList(evidence.unreadable));
    case "C03":
      return known(Object.keys((evidence.detected as Record<string, unknown> | undefined) ?? {}));
    case "C10":
      return known([...new Set(stringList(evidence.mismatch).map((k) => SLOT_BY_NAME_SOURCE[k] ?? ""))]);
    default: {
      const slot = SLOT_BY_CODE[check.code];
      return slot ? [slot] : [];
    }
  }
}

export interface ReadSummary {
  /** i18n key of the identifier's label; the value is already masked by the server. */
  idLabel: "gstin" | "udyam" | "idNumber" | "consumerNumber";
  id: string | null;
  name: string | null;
}

function text(fields: NonNullable<DocumentView["fields"]>, key: string): string | null {
  const v = fields[key];
  return v === null || v === undefined || v === "" ? null : String(v);
}

/** The short "what we read" line for a slot. Null when nothing was read. */
export function readSummary(doc: DocumentView): ReadSummary | null {
  const f = doc.fields;
  if (doc.status !== "OK" || !f) {
    return null;
  }
  switch (doc.docType) {
    case "GST_CERTIFICATE":
      return { idLabel: "gstin", id: text(f, "gstin"), name: text(f, "tradeName") ?? text(f, "legalName") };
    case "UDYAM_CERTIFICATE":
      return { idLabel: "udyam", id: text(f, "udyamNumber"), name: text(f, "enterpriseName") };
    case "SIGNATORY_ID":
      return { idLabel: "idNumber", id: text(f, "idNumber"), name: text(f, "name") };
    case "ADDRESS_PROOF":
      return { idLabel: "consumerNumber", id: text(f, "consumerNumber"), name: text(f, "billedTo") };
  }
}
