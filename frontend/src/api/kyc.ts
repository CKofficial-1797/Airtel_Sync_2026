import { request } from "./client";
import type { ConsentNotice, ConsentRequest, DocType, DocumentView, KycSubmitResult, KycView } from "./types";

function kycPath(orderId: string, suffix = ""): string {
  return `/api/orders/${encodeURIComponent(orderId)}/kyc${suffix}`;
}

export function getConsentNotice(language: string, signal?: AbortSignal): Promise<ConsentNotice> {
  return request<ConsentNotice>(`/api/kyc/consent-notice?lang=${encodeURIComponent(language)}`, { signal });
}

export function getKyc(orderId: string): Promise<KycView> {
  return request<KycView>(kycPath(orderId));
}

export function giveConsent(orderId: string, body: ConsentRequest): Promise<KycView> {
  return request<KycView>(kycPath(orderId, "/consent"), { method: "POST", body });
}

export function withdrawConsent(orderId: string): Promise<KycView> {
  return request<KycView>(kycPath(orderId, "/consent/withdraw"), { method: "POST" });
}

export function uploadDocument(orderId: string, docType: DocType, file: File): Promise<DocumentView> {
  const form = new FormData();
  form.append("docType", docType);
  form.append("file", file);
  return request<DocumentView>(kycPath(orderId, "/documents"), { method: "POST", body: form });
}

export function submitKyc(orderId: string): Promise<KycSubmitResult> {
  return request<KycSubmitResult>(kycPath(orderId, "/submit"), { method: "POST" });
}
