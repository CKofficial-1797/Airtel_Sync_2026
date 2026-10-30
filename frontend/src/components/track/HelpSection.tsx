import { PhoneCall } from "lucide-react";
import { useTranslation } from "react-i18next";

const FAQS = ["when", "changeSlot", "hold"];

export function HelpSection({ onCallback }: { onCallback: () => void }) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="help-heading" className="rounded-2xl border border-slate-200 bg-white p-4">
      <h2 id="help-heading" className="mb-1 font-semibold text-slate-900">
        {t("track.help.heading")}
      </h2>
      {FAQS.map((key) => (
        <details key={key} className="border-b border-slate-100 text-sm last:border-0">
          <summary className="flex min-h-11 cursor-pointer items-center font-medium text-slate-800">{t(`track.help.${key}Q`)}</summary>
          <p className="pb-3 text-slate-700">{t(`track.help.${key}A`)}</p>
        </details>
      ))}
      <button
        type="button"
        onClick={onCallback}
        className="mt-3 flex min-h-11 w-full items-center justify-center gap-2 rounded-xl border border-slate-300 px-4 font-medium text-slate-800 hover:bg-slate-50"
      >
        <PhoneCall className="size-4" aria-hidden />
        {t("track.help.callback")}
      </button>
    </section>
  );
}
