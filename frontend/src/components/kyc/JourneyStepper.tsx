import { Check } from "lucide-react";
import { useTranslation } from "react-i18next";

const STEPS = ["advisor", "quote", "kyc", "track"] as const;
export type JourneyStep = (typeof STEPS)[number];

/** Advisor → Quote → KYC → Track. State is carried by a tick, weight and screen-reader text, not colour alone. */
export function JourneyStepper({ current }: { current: JourneyStep }) {
  const { t } = useTranslation();
  const currentIndex = STEPS.indexOf(current);
  return (
    <nav aria-label={t("journey.label")} className="border-b border-slate-200 bg-white px-3 py-2">
      <ol className="flex items-center justify-between gap-1">
        {STEPS.map((step, i) => {
          const done = i < currentIndex;
          const active = i === currentIndex;
          return (
            <li key={step} aria-current={active ? "step" : undefined} className="flex min-w-0 items-center gap-1 text-xs">
              <span
                className={`flex size-6 shrink-0 items-center justify-center rounded-full font-semibold ${
                  active ? "bg-teal-700 text-white" : done ? "bg-teal-100 text-teal-900" : "bg-slate-100 text-slate-600"
                }`}
                aria-hidden
              >
                {done ? <Check className="size-3.5" /> : i + 1}
              </span>
              <span className={`truncate ${active ? "font-semibold text-slate-900" : "text-slate-600"}`}>{t(`journey.${step}`)}</span>
              {(done || active) && <span className="sr-only">{t(done ? "journey.done" : "journey.current")}</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
