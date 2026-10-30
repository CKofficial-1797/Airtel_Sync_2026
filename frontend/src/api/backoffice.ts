import { queueQueryString, readBackofficeKey, type QueueFilters } from "../lib/backoffice";
import { request } from "./client";

export type LeadKind = "HISTORICAL" | "SESSION" | "ORDER";

export interface ScoreReason {
  labelEn: string;
  labelHi: string;
  direction: "+" | "-";
  contribution: number;
}

export interface Assignment {
  kind: LeadKind;
  ref: string;
  channel: string;
  status: string;
  assigneeLabel: string | null;
  note: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface QueueRow {
  kind: LeadKind;
  ref: string;
  displayRef: string;
  businessName: string | null;
  businessType: string | null;
  segment: string | null;
  city: string | null;
  tier: number | null;
  employees: number | null;
  sites: number | null;
  gstinStatus: string | null;
  stoppedAt: string;
  lastActivity: string | null;
  score: number;
  probability: number;
  band: string;
  topReasons: ScoreReason[];
  suggestedAction: string;
  recommendedChannel: string;
  channelWhy: string;
  quoteMonthlyInr: number | null;
  status: string;
  assignment: Assignment | null;
}

export interface QueuePage {
  rows: QueueRow[];
  page: number;
  size: number;
  total: number;
}

export interface QuoteSummary {
  monthlyInr: number | null;
  oneTimeInr: number | null;
  contractMonths: number | null;
  lines: { productName: string; quantity: number }[];
}

export interface AssignmentEvent {
  channel: string;
  status: string;
  assigneeLabel: string | null;
  note: string | null;
  createdAt: string;
}

export interface LeadDetail {
  lead: QueueRow;
  profile: Record<string, unknown> | null;
  quote: QuoteSummary | null;
  failedChecks: { code: string; messageEn: string; messageHi: string; fixHintEn: string | null; fixHintHi: string | null }[];
  recentMessages: { role: string; content: string; createdAt: string | null }[];
  contactNote: string;
  history: AssignmentEvent[];
}

export interface Reengagement {
  processTotal: number;
  priceTotal: number;
  byReason: { reason: string; leads: number }[];
  leads: QueuePage;
}

export interface LiftRow {
  decile: number;
  leads: number;
  activations: number;
  lift: number;
  cumulativeCapture: number;
}

export interface Insights {
  funnel: { stage: string; leads: number }[];
  segmentEconomics: {
    segment: string;
    leads: number;
    activations: number;
    conversion: number;
    cacPerActivation: number;
    avgMonthlyBundle: number;
    paybackMonths: number;
    churn6m: number;
  }[];
  sourceEconomics: { source: string; leads: number; activations: number; conversion: number; cacPerActivation: number }[];
  misSelling: { activatedChecked: number; bundlesWithViolations: number; misSoldPercent: number; registryActiveViolations: number };
  model: { variant: string; variantName: string; auc: number; top20Capture: number; juneAuc: number; juneTop20Capture: number; liftTable: LiftRow[] };
  liveFunnel: { sessions: number; recommended: number; checkout: number; kycVerified: number; active: number };
}

function headers(key?: string): Record<string, string> {
  return { "X-Backoffice-Key": key ?? readBackofficeKey() ?? "" };
}

function leadPath(kind: string, ref: string): string {
  return `/api/backoffice/leads/${encodeURIComponent(kind)}/${encodeURIComponent(ref)}`;
}

export function getQueue(filters: QueueFilters, signal?: AbortSignal): Promise<QueuePage> {
  return request(`/api/backoffice/queue${queueQueryString(filters)}`, { headers: headers(), signal });
}

/** Asks for one row with the key being tried: a 401 means the passcode is wrong. */
export function checkKey(key: string): Promise<QueuePage> {
  return request(`/api/backoffice/queue${queueQueryString({ size: 1 })}`, { headers: headers(key) });
}

export function getLead(kind: string, ref: string, signal?: AbortSignal): Promise<LeadDetail> {
  return request(leadPath(kind, ref), { headers: headers(), signal });
}

export function assignLead(kind: string, ref: string, body: { channel: string; assigneeLabel?: string; note?: string }): Promise<LeadDetail> {
  return request(`${leadPath(kind, ref)}/assign`, { method: "POST", body, headers: headers() });
}

export function setLeadStatus(kind: string, ref: string, body: { status: string; note?: string }): Promise<LeadDetail> {
  return request(`${leadPath(kind, ref)}/status`, { method: "POST", body, headers: headers() });
}

export function getReengagement(page: number, size: number, signal?: AbortSignal): Promise<Reengagement> {
  return request(`/api/backoffice/reengagement${queueQueryString({ page, size })}`, { headers: headers(), signal });
}

export function getInsights(signal?: AbortSignal): Promise<Insights> {
  return request("/api/backoffice/insights", { headers: headers(), signal });
}
