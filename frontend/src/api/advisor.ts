import { request } from "./client";
import type { AdvisorReply, BusinessTypeRef, SessionCreated, SessionView } from "./types";

export function createSession(uiLanguage: "en" | "hi"): Promise<SessionCreated> {
  return request<SessionCreated>("/api/advisor/sessions", { method: "POST", body: { uiLanguage } });
}

export function getSession(sessionId: string): Promise<SessionView> {
  return request<SessionView>(`/api/advisor/sessions/${encodeURIComponent(sessionId)}`);
}

export function sendMessage(sessionId: string, text: string): Promise<AdvisorReply> {
  return request<AdvisorReply>(`/api/advisor/sessions/${encodeURIComponent(sessionId)}/messages`, {
    method: "POST",
    body: { text },
  });
}

let businessTypesCache: Promise<BusinessTypeRef[]> | null = null;

export function getBusinessTypes(): Promise<BusinessTypeRef[]> {
  businessTypesCache ??= request<BusinessTypeRef[]>("/api/reference/business-types").catch((e: unknown) => {
    businessTypesCache = null;
    throw e;
  });
  return businessTypesCache;
}
