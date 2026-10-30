/** "32TTNXB7384T1ZW" -> "32TTN*****1ZW" (same rule as the backend Masking util). */
export function maskGstin(gstin: string | null | undefined): string {
  if (!gstin) {
    return "";
  }
  if (gstin.includes("*")) {
    return gstin; // already masked by the server
  }
  return gstin.length < 9 ? "*****" : `${gstin.slice(0, 5)}*****${gstin.slice(-3)}`;
}
