const inr = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });

/** 123456 -> "₹1,23,456" (Indian digit grouping, whole rupees). */
export function formatInr(amount: number): string {
  return inr.format(amount);
}

/** "2026-07-20" -> "20 Jul 2026" (or the Hindi equivalent). Parsed as a local date, no timezone shift. */
export function formatDate(isoDate: string | null | undefined, language: string): string {
  if (!isoDate) {
    return "";
  }
  const [y, m, d] = isoDate.slice(0, 10).split("-").map(Number);
  if (!y || !m || !d) {
    return isoDate;
  }
  return new Intl.DateTimeFormat(language === "hi" ? "hi-IN" : "en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(y, m - 1, d));
}
