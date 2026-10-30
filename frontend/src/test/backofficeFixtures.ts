import type { Insights, LeadDetail, QueuePage, QueueRow, Reengagement } from "../api/backoffice";

export function queueRow(overrides: Partial<QueueRow> = {}): QueueRow {
  return {
    kind: "SESSION",
    ref: "11111111-1111-1111-1111-111111111111",
    displayRef: "11111111",
    businessName: null,
    businessType: "Dental clinic",
    segment: "Professional offices",
    city: "Kochi",
    tier: 2,
    employees: 8,
    sites: 1,
    gstinStatus: "ACTIVE",
    stoppedAt: "Advisor: READY_FOR_CHECKOUT",
    lastActivity: "2026-07-14T10:00:00Z",
    score: 72,
    probability: 0.0912,
    band: "WARM",
    topReasons: [{ labelEn: "Active GSTIN", labelHi: "सक्रिय GSTIN", direction: "+", contribution: 1.235 }],
    suggestedAction: "FOLLOW_UP_QUOTE",
    recommendedChannel: "INSIDE_SALES",
    channelWhy: "Saw a quote of ₹2,499/month and didn't proceed.",
    quoteMonthlyInr: 2499,
    status: "NEW",
    assignment: null,
    ...overrides,
  };
}

export function queuePage(rows: QueueRow[] = [queueRow()], total = rows.length, page = 0): QueuePage {
  return { rows, page, size: 25, total };
}

export const reengagement: Reengagement = {
  processTotal: 1291,
  priceTotal: 621,
  byReason: [
    { reason: "Owner had no time for a visit", leads: 531 },
    { reason: "Visit not scheduled by sales", leads: 296 },
  ],
  leads: queuePage([queueRow({ kind: "HISTORICAL", ref: "L00001", displayRef: "L00001", businessName: "Balaji Dental Care LLP", stoppedAt: "Contacted: Owner had no time for a visit" })], 1291),
};

export function leadDetail(overrides: Partial<LeadDetail> = {}): LeadDetail {
  return {
    lead: queueRow(),
    profile: { businessType: "Dental clinic", city: "Kochi", employees: 8, sites: 1, gstin: "32TTN*****1ZW" },
    quote: { monthlyInr: 2499, oneTimeInr: 500, contractMonths: 12, lines: [{ productName: "Business Fibre", quantity: 1 }] },
    failedChecks: [],
    recentMessages: [{ role: "user", content: "We need internet for the clinic", createdAt: "2026-07-14T10:00:00Z" }],
    contactNote: "Contact details aren't collected yet, so this lead can't be reached from here.",
    history: [],
    ...overrides,
  };
}

export const insights: Insights = {
  funnel: [
    { stage: "Lead", leads: 5000 },
    { stage: "Contacted", leads: 3406 },
    { stage: "Needs assessed", leads: 1974 },
    { stage: "Quote accepted", leads: 861 },
    { stage: "KYC complete", leads: 588 },
    { stage: "Activated", leads: 368 },
  ],
  segmentEconomics: [
    { segment: "Micro retail & services", leads: 2709, activations: 110, conversion: 4.1, cacPerActivation: 20007, avgMonthlyBundle: 2500, paybackMonths: 8, churn6m: 20 },
    { segment: "Professional offices", leads: 1247, activations: 138, conversion: 11.1, cacPerActivation: 9000, avgMonthlyBundle: 4300, paybackMonths: 2.1, churn6m: 10 },
    { segment: "Small multi-site businesses", leads: 755, activations: 83, conversion: 11, cacPerActivation: 9500, avgMonthlyBundle: 9000, paybackMonths: 1.1, churn6m: 9 },
    { segment: "Small manufacturers", leads: 289, activations: 37, conversion: 12.8, cacPerActivation: 8000, avgMonthlyBundle: 6000, paybackMonths: 1.3, churn6m: 8 },
  ],
  sourceEconomics: [{ source: "Referral", leads: 413, activations: 61, conversion: 14.8, cacPerActivation: 3000 }],
  misSelling: { activatedChecked: 368, bundlesWithViolations: 48, misSoldPercent: 13, registryActiveViolations: 56 },
  model: {
    variant: "b",
    variantName: "advisor-compatible",
    auc: 0.699,
    top20Capture: 0.375,
    juneAuc: 0.714,
    juneTop20Capture: 0.394,
    liftTable: [
      { decile: 1, leads: 500, activations: 75, lift: 2.038, cumulativeCapture: 0.2038 },
      { decile: 2, leads: 500, activations: 63, lift: 1.712, cumulativeCapture: 0.375 },
    ],
  },
  liveFunnel: { sessions: 9, recommended: 7, checkout: 4, kycVerified: 2, active: 1 },
};
