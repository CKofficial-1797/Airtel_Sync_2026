import { useTranslation } from "react-i18next";
import type { TrackerEvent } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { formatDate } from "../../lib/format";
import { pickText } from "../../lib/kyc";

const SHOWN = 6;

/** Latest first, in the words the server chose for the owner. Dates are business dates, like the timeline. */
export function ActivityList({ events }: { events: TrackerEvent[] }) {
  const { t, i18n } = useTranslation();
  const language = currentLanguage();
  return (
    <section aria-labelledby="activity-heading" className="rounded-2xl border border-slate-200 bg-white p-4">
      <h2 id="activity-heading" className="mb-2 font-semibold text-slate-900">
        {t("track.activity")}
      </h2>
      <ul className="space-y-2 text-sm">
        {events.slice(0, SHOWN).map((e, i) => (
          <li key={`${e.at}-${i}`} className="flex justify-between gap-3">
            <span className="min-w-0 break-words text-slate-800">{pickText(language, e.textEn, e.textHi)}</span>
            <span className="shrink-0 text-slate-600 tabular-nums">{formatDate(e.businessDate, i18n.language)}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
