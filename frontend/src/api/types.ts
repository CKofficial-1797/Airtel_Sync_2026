// TypeScript mirrors of the Spring JSON (see docs/HANDOFF.md). Money is integer rupees.

export type Stage =
  | "COLLECTING"
  | "RECOMMENDED"
  | "CUSTOMISING"
  | "READY_FOR_CHECKOUT"
  | "OUT_OF_SCOPE"
  | "CHECKOUT_DONE";

export type GstinStatus = "VERIFIED_ACTIVE" | "INACTIVE" | "INVALID" | "DECLARED_UNVERIFIED" | "NONE";

export interface Reason {
  code: string;
  message: string;
}

export interface EligibilityResult {
  productId: string;
  productName: string;
  eligible: boolean;
  conditional: boolean;
  condition: string | null;
  reasons: Reason[];
}

export interface RecommendationLine {
  productId: string;
  productName: string;
  category: string;
  unit: string;
  quantity: number;
  unitPriceInr: number;
  monthlyTotalInr: number;
  setupFeeInr: number;
  role: "PRIMARY" | "INCLUDED" | "NEED" | "ADD_ON" | "UPGRADE";
  replaces: string | null;
  why: string;
  conditional: boolean;
}

export interface QuoteLine {
  productId: string;
  productName: string;
  unit: string;
  quantity: number;
  unitPriceInr: number;
  monthlyTotalInr: number;
  setupFeeInr: number;
  minContractMonths: number;
  installDays: number;
  conditional: boolean;
}

export interface RemovedItem {
  productId: string;
  productName: string;
  quantity: number;
  reasons: Reason[];
}

export interface AddOn {
  productId: string;
  productName: string;
  unit: string;
  unitPriceInr: number;
  defaultQuantity: number;
  setupFeePerUnitInr: number;
  conditional: boolean;
}

export interface SpendComparison {
  currentSpendInr: number;
  newMonthlyInr: number;
  differenceInr: number;
}

export interface Quote {
  pricingMode: "MIN" | "MID";
  lines: QuoteLine[];
  monthlyTotalInr: number;
  oneTimeTotalInr: number;
  contractMonths: number;
  maxInstallDays: number;
  estimatedActivationDate: string | null;
  conditional: boolean;
  removed: RemovedItem[];
  availableAddOns: AddOn[];
  ineligibleProducts: EligibilityResult[];
  comparison: SpendComparison | null;
  notes: string[];
}

export interface GstinAutofill {
  legalName: string;
  tradeName: string;
  state: string;
  principalPlaceOfBusiness: string;
}

export interface ResolvedProfile {
  businessType: string | null;
  segment: string;
  segmentCode: string;
  segmentSource: string;
  city: string | null;
  cityTier: number;
  cityState: string | null;
  employees: number;
  sites: number;
  gstinStatus: GstinStatus;
  gstin: string | null;
  gstinRegistryStatus: string | null;
  gstinErrors: string[];
  gstinAutofill: GstinAutofill | null;
  warnings: string[];
}

export interface Recommendation {
  resolvedProfile: ResolvedProfile;
  segment: string;
  included: RecommendationLine[];
  optionalAddOns: RecommendationLine[];
  excluded: EligibilityResult[];
  notes: string[];
  quote: Quote;
}

/** The facts the advisor collected (Spring BusinessProfile). */
export interface Profile {
  businessType?: string;
  segment?: string;
  city?: string;
  cityTier?: number;
  employees?: number;
  sites?: number;
  gstin?: string;
  gstinDeclared?: boolean;
  currentInternet?: string;
  monthlyTelecomSpendInr?: number;
  fibreUnavailable?: boolean;
  needs?: string[];
  officeUsers?: number;
  mobileLines?: number;
  cameras?: number;
  vehicles?: number;
  machines?: number;
}

export interface SessionCreated {
  sessionId: string;
  greeting: string;
}

export interface AdvisorReply {
  sessionId: string;
  reply: string;
  stage: Stage;
  profile: Profile | null;
  recommendation: Recommendation | null;
  quote: Quote | null;
  detectedLanguage: string | null;
}

export interface SessionMessage {
  role: "user" | "assistant";
  content: string;
  createdAt: string;
}

export interface SessionView {
  sessionId: string;
  createdAt: string;
  updatedAt: string;
  uiLanguage: string | null;
  detectedLanguage: string | null;
  stage: Stage;
  profile: Profile | null;
  recommendation: Recommendation | null;
  quote: Quote | null;
  messages: SessionMessage[];
}

export interface BusinessTypeRef {
  businessType: string;
  segment: string;
}

export interface Product {
  productId: string;
  category: string;
  productName: string;
  description: string;
  unit: string;
}

export interface QuoteItem {
  productId: string;
  quantity: number;
}

export interface OrderCreated {
  orderId: string;
  orderRef: string;
  status: string;
  quote: Quote;
}

export interface OrderEvent {
  status: string;
  note: string | null;
  createdAt: string;
}

export interface OrderView {
  orderId: string;
  orderRef: string;
  status: string;
  createdAt: string;
  quote: Quote;
  profile: Profile;
  events: OrderEvent[];
}

export interface ApiErrorBody {
  status: number;
  error: string;
  code?: string;
  message: string;
  path: string;
  errors?: string[];
  quote?: Quote;
}
