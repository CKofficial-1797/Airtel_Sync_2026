import type { Quote, QuoteLine, Recommendation } from "../api/types";

export function line(productId: string, productName: string, quantity: number, unitPriceInr: number, setup = 0): QuoteLine {
  return {
    productId,
    productName,
    unit: "per site",
    quantity,
    unitPriceInr,
    monthlyTotalInr: unitPriceInr * quantity,
    setupFeeInr: setup * quantity,
    minContractMonths: 12,
    installDays: 5,
    conditional: false,
  };
}

/** Kirana store, tier 2, no GST: P01 x1 + P07 x2 = ₹1,597/month (catalogue MIN prices). */
export function kiranaQuote(overrides: Partial<Quote> = {}): Quote {
  const lines = [line("P01", "Biz Fibre 100", 1, 999), line("P07", "Business Postpaid 299", 2, 299)];
  return {
    pricingMode: "MIN",
    lines,
    monthlyTotalInr: 1597,
    oneTimeTotalInr: 0,
    contractMonths: 12,
    maxInstallDays: 5,
    estimatedActivationDate: "2026-07-20",
    conditional: false,
    removed: [],
    availableAddOns: [
      { productId: "P23", productName: "Cloud CCTV", unit: "per camera", unitPriceInr: 199, defaultQuantity: 2, setupFeePerUnitInr: 1800, conditional: false },
      { productId: "P11", productName: "Static IP", unit: "per IP", unitPriceInr: 200, defaultQuantity: 1, setupFeePerUnitInr: 0, conditional: false },
    ],
    ineligibleProducts: [],
    comparison: null,
    notes: ["Prices as per Accel Business catalogue (digital price = catalogue minimum)"],
    ...overrides,
  };
}

export function kiranaRecommendation(): Recommendation {
  return {
    resolvedProfile: {
      businessType: "Kirana store",
      segment: "Micro retail & services",
      segmentCode: "micro",
      segmentSource: "BUSINESS_TYPE",
      city: "Ludhiana",
      cityTier: 2,
      cityState: "Punjab",
      employees: 3,
      sites: 1,
      gstinStatus: "NONE",
      gstin: null,
      gstinRegistryStatus: null,
      gstinErrors: [],
      gstinAutofill: null,
      warnings: [],
    },
    segment: "Micro retail & services",
    included: [
      { productId: "P01", productName: "Biz Fibre 100", category: "Connectivity", unit: "per site", quantity: 1, unitPriceInr: 999, monthlyTotalInr: 999, setupFeeInr: 0, role: "PRIMARY", replaces: null, why: "100 Mbps business fibre for 1 site(s)", conditional: false },
      { productId: "P07", productName: "Business Postpaid 299", category: "Connectivity", unit: "per line", quantity: 2, unitPriceInr: 299, monthlyTotalInr: 598, setupFeeInr: 0, role: "INCLUDED", replaces: null, why: "2 Business Postpaid 299 line(s) for the owner and staff", conditional: false },
    ],
    optionalAddOns: [],
    excluded: [],
    notes: [],
    quote: kiranaQuote(),
  };
}
