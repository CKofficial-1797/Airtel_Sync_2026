// sessionStorage can throw (private mode, blocked storage); the gate then simply asks again.
const KEY = "udyam.backofficeKey";

export function readBackofficeKey(): string | null {
  try {
    return window.sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
}

/** False when the key could not be kept: the staff member would be asked again on every page. */
export function saveBackofficeKey(value: string): boolean {
  try {
    window.sessionStorage.setItem(KEY, value);
    return true;
  } catch {
    return false;
  }
}

export function clearBackofficeKey(): void {
  try {
    window.sessionStorage.removeItem(KEY);
  } catch {
    // ignore: storage unavailable
  }
}

export interface QueueFilters {
  kind?: string;
  segment?: string;
  tier?: string;
  band?: string;
  channel?: string;
  status?: string;
  page?: number;
  size?: number;
  sort?: string;
}

/** "?kind=SESSION&band=HOT&page=2": empty filters are left out, so "all" never reaches the server as a value. */
export function queueQueryString(filters: QueueFilters): string {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(filters)) {
    if (value !== undefined && value !== "" && !(name === "page" && value === 0)) {
      params.set(name, String(value));
    }
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

/** "ON_HOLD: SITE_NOT_FEASIBLE" -> "On hold: Site not feasible". Data text ("Contacted: Price too high") stays as it is. */
export function formatStage(text: string): string {
  return text
    .split(": ")
    .map((part) => (/^[A-Z_]+$/.test(part) ? (part.charAt(0) + part.slice(1).toLowerCase()).replaceAll("_", " ").replace(/^Kyc\b/, "KYC") : part))
    .join(": ");
}

export const SEGMENTS = ["Micro retail & services", "Professional offices", "Small multi-site businesses", "Small manufacturers"] as const;
export const MICRO_SEGMENT = SEGMENTS[0];
export const BANDS = ["HOT", "WARM", "COLD"] as const;
export const CHANNELS = ["DIGITAL_NUDGE", "INSIDE_SALES", "FIELD_SALES", "CHANNEL_PARTNER"] as const;
export const STATUSES = ["NEW", "CONTACTED", "CONVERTED", "LOST"] as const;
