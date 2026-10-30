import { CalendarClock } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { TrackerView } from "../../api/types";
import { formatDate } from "../../lib/format";

const NO_PROMISE = ["KYC_REJECTED", "CANCELLED"];

/** Honest dates: real dates once verified, "N days after verification" before, no stale promise on hold. */
export function EtaCard({ view }: { view: TrackerView }) {
  const { t, i18n } = useTranslation();
  if (NO_PROMISE.includes(view.currentStatus) || view.items.length === 0) {
    return null;
  }
  const active = view.currentStatus === "ACTIVE";
  const date = formatDate(view.expectedActivationDate, i18n.language);

  let headline: string;
  if (view.onHold) {
    headline = t("track.eta.onHold");
  } else if (date) {
    headline = t(active ? "track.eta.activeSince" : "track.eta.by", { date });
  } else {
    headline = t("track.eta.afterVerification", { count: view.etaDaysAfterVerification });
  }

  return (
    <section aria-labelledby="eta-heading" className="rounded-2xl border border-slate-200 bg-white p-4" data-testid="eta-card">
      <h2 id="eta-heading" className="flex items-center gap-2 font-semibold text-slate-900">
        <CalendarClock className="size-5 text-teal-700" aria-hidden />
        {t("track.eta.heading")}
      </h2>
      <p className="mt-1 text-lg font-bold text-slate-900">{headline}</p>
      {!view.onHold && !active && (
        <ul className="mt-2 space-y-1 text-sm text-slate-700">
          {view.items.map((item) => (
            <li key={item.productName} className="flex justify-between gap-3">
              <span className="min-w-0 break-words">
                {item.productName}
                {item.quantity > 1 && ` ×${item.quantity}`}
              </span>
              <span className="shrink-0 font-medium tabular-nums text-slate-900">
                {item.etaDate ? formatDate(item.etaDate, i18n.language) : t("track.eta.days", { count: item.etaDays })}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
