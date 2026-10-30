import { ArrowRight, Loader2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { Quote } from "../api/types";
import { formatDate, formatInr } from "../lib/format";

interface Props {
  quote: Quote;
  updating: boolean;
  placing: boolean;
  onAccept: () => void;
}

/** Sticky bottom bar: totals straight from the engine's quote JSON + the accept CTA. */
export function TotalsBar({ quote, updating, placing, onAccept }: Props) {
  const { t, i18n } = useTranslation();
  return (
    <div className="sticky bottom-0 z-20 border-t border-slate-200 bg-white px-4 pb-[max(env(safe-area-inset-bottom),0.75rem)] pt-3 shadow-[0_-4px_16px_rgba(15,23,42,0.06)]">
      <div className={`flex items-end justify-between gap-3 transition ${updating ? "opacity-50" : ""}`} aria-live="polite" aria-busy={updating}>
        <div>
          <p className="text-xs text-slate-600">{t("totals.monthly")}</p>
          <p className="text-3xl font-bold text-slate-900 tabular-nums" data-testid="total-monthly">
            {formatInr(quote.monthlyTotalInr)}
          </p>
        </div>
        <div className="text-right text-sm text-slate-600">
          <p>
            {t("totals.oneTime")}: <span className="font-semibold text-slate-900 tabular-nums">{formatInr(quote.oneTimeTotalInr)}</span>
          </p>
          <p>{t("totals.contract", { count: quote.contractMonths })}</p>
        </div>
      </div>
      {quote.estimatedActivationDate && (
        <p className="mt-1 text-sm text-slate-600">
          {t("totals.activation", { date: formatDate(quote.estimatedActivationDate, i18n.language) })}
        </p>
      )}
      <p className="text-xs text-slate-500">{updating ? t("quote.updating") : t("totals.priceNote")}</p>
      <button
        type="button"
        onClick={onAccept}
        disabled={updating || placing || quote.lines.length === 0}
        className="mt-3 flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800 disabled:opacity-60"
      >
        {placing ? (
          <>
            <Loader2 className="size-4 animate-spin" aria-hidden />
            {t("totals.placing")}
          </>
        ) : (
          <>
            {t("totals.accept")}
            <ArrowRight className="size-4" aria-hidden />
          </>
        )}
      </button>
    </div>
  );
}
