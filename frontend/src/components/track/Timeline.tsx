import { CircleAlert, CircleCheck, CircleDot, Circle, type LucideIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { StepState, TrackerStep } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { formatDate } from "../../lib/format";
import { pickText } from "../../lib/kyc";

const STATE: Record<StepState, { icon: LucideIcon; color: string; line: string }> = {
  DONE: { icon: CircleCheck, color: "text-green-800", line: "bg-green-700" },
  CURRENT: { icon: CircleDot, color: "text-teal-800", line: "bg-slate-300" },
  BLOCKED: { icon: CircleAlert, color: "text-amber-800", line: "bg-slate-300" },
  UPCOMING: { icon: Circle, color: "text-slate-500", line: "bg-slate-300" },
};

/** The 5 customer steps. Each state has an icon AND a word, never colour alone. */
export function Timeline({ steps, onHold }: { steps: TrackerStep[]; onHold: boolean }) {
  const { t, i18n } = useTranslation();
  const language = currentLanguage();
  return (
    <section aria-labelledby="timeline-heading" className="rounded-2xl border border-slate-200 bg-white p-4">
      <h2 id="timeline-heading" className="mb-3 font-semibold text-slate-900">
        {t("track.timeline.heading")}
      </h2>
      <ol>
        {steps.map((step, i) => {
          const { icon: Icon, color, line } = STATE[step.state];
          const date = formatDate(step.date, i18n.language);
          return (
            <li key={step.key} className="flex gap-3" aria-current={step.state === "CURRENT" ? "step" : undefined} data-testid={`step-${step.key}`}>
              <div className="flex flex-col items-center">
                <Icon className={`size-6 shrink-0 ${color}`} aria-hidden />
                {i < steps.length - 1 && <span className={`my-1 w-0.5 flex-1 ${line}`} aria-hidden />}
              </div>
              <div className="min-w-0 flex-1 pb-4">
                <p className={`font-medium ${step.state === "UPCOMING" ? "text-slate-700" : "text-slate-900"}`}>
                  {pickText(language, step.labelEn, step.labelHi)}
                </p>
                <p className="text-sm text-slate-600">
                  {/* A hold asks nothing of the owner, so it mustn't read like a blocked step that does. */}
                  <span className={`font-medium ${color}`}>{t(`track.timeline.state.${onHold && step.state === "BLOCKED" ? "ON_HOLD" : step.state}`)}</span>
                  {date && ` · ${step.state === "DONE" ? date : t("track.timeline.planned", { date })}`}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
