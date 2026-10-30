import { Minus, Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ScoreReason } from "../../api/backoffice";

/** Why the lead scored as it did: each bar is one reason, with its sign written out as well as coloured. */
export function ReasonBars({ reasons }: { reasons: ScoreReason[] }) {
  const { t, i18n } = useTranslation();
  if (reasons.length === 0) {
    return <p className="text-sm text-slate-600">{t("backoffice.lead.noReasons")}</p>;
  }
  const largest = Math.max(...reasons.map((r) => Math.abs(r.contribution)));
  return (
    <ul className="space-y-3">
      {reasons.map((r) => {
        const positive = r.direction === "+";
        const Icon = positive ? Plus : Minus;
        return (
          <li key={r.labelEn}>
            <div className="flex items-center gap-1 text-sm text-slate-800">
              <Icon className={`size-4 shrink-0 ${positive ? "text-teal-700" : "text-red-700"}`} aria-hidden />
              <span className="sr-only">{t(positive ? "backoffice.lead.raises" : "backoffice.lead.lowers")}</span>
              {i18n.language === "hi" ? r.labelHi : r.labelEn}
            </div>
            <div className="ml-5 mt-1 h-2 rounded bg-slate-100" aria-hidden>
              <div className={`h-2 rounded ${positive ? "bg-teal-700" : "bg-red-700"}`} style={{ width: `${(Math.abs(r.contribution) / largest) * 100}%` }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
