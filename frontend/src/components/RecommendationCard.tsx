import { ArrowRight, BadgeCheck, ShieldAlert } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { Quote, Recommendation, Stage } from "../api/types";
import { formatInr } from "../lib/format";

interface Props {
  recommendation: Recommendation | null;
  quote: Quote;
  stage: Stage;
  onOpenQuote: () => void;
}

/** The bundle, rendered ONLY from the engine's JSON (never from the chat text). */
export function RecommendationCard({ recommendation, quote, stage, onOpenQuote }: Props) {
  const { t } = useTranslation();
  const why = new Map(recommendation?.included.map((l) => [l.productId, l.why]) ?? []);
  const ready = stage === "READY_FOR_CHECKOUT";
  const done = stage === "CHECKOUT_DONE";
  const customised = stage === "CUSTOMISING";

  return (
    <section
      aria-label={t("card.recommended")}
      className={`rounded-2xl border bg-teal-50 p-4 shadow-sm ${ready ? "border-teal-700 ring-2 ring-teal-700/30" : "border-teal-200"}`}
    >
      <p className="text-xs font-semibold uppercase tracking-wide text-teal-800">
        {customised ? t("card.updated") : t("card.recommended")}
      </p>
      <p className="mt-1 text-slate-900">
        <span className="text-3xl font-bold tabular-nums" data-testid="card-monthly">
          {formatInr(quote.monthlyTotalInr)}
        </span>
        <span className="text-sm text-slate-600">{t("card.perMonth")}</span>
      </p>
      <p className="mt-0.5 text-sm text-slate-600">
        <span data-testid="card-setup">{t("card.setup", { amount: formatInr(quote.oneTimeTotalInr) })}</span>
        {" · "}
        <span data-testid="card-contract">{t("card.contract", { count: quote.contractMonths })}</span>
      </p>

      <h3 className="mt-3 text-sm font-semibold text-slate-900">{t("card.included")}</h3>
      <ul className="mt-1 space-y-2">
        {quote.lines.map((line) => (
          <li key={line.productId} className="text-sm">
            <span className="font-medium text-slate-900">
              {line.productName} × {line.quantity}
            </span>
            {why.get(line.productId) && <span className="block text-slate-600">{why.get(line.productId)}</span>}
            {line.conditional && (
              <span className="mt-1 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-800">
                <ShieldAlert className="size-3" aria-hidden />
                {t("card.conditional")}
              </span>
            )}
          </li>
        ))}
      </ul>

      {done ? (
        <p className="mt-4 flex items-center gap-2 font-medium text-green-700">
          <BadgeCheck className="size-5" aria-hidden />
          {t("card.orderPlaced")}
        </p>
      ) : (
        <button
          type="button"
          onClick={onOpenQuote}
          className={`mt-4 flex min-h-12 w-full items-center justify-center gap-2 rounded-xl px-4 font-semibold transition ${
            ready
              ? "bg-teal-700 text-white hover:bg-teal-800"
              : "border border-teal-700 bg-white text-teal-800 hover:bg-teal-50"
          }`}
        >
          {ready ? t("card.reviewContinue") : t("card.viewQuote")}
          <ArrowRight className="size-4" aria-hidden />
        </button>
      )}
    </section>
  );
}
