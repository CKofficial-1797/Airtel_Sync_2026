import { PauseCircle } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { TrackerView } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { pickText } from "../../lib/kyc";

/** Amber and calm: what happened and what happens next, so there's no reason to call. */
export function HoldBanner({ onHold }: { onHold: NonNullable<TrackerView["onHold"]> }) {
  const { t } = useTranslation();
  const language = currentLanguage();
  return (
    <section role="status" className="flex items-start gap-3 rounded-2xl border border-amber-300 bg-amber-100 p-4 text-amber-950" data-testid="hold-banner">
      <PauseCircle className="mt-0.5 size-6 shrink-0 text-amber-800" aria-hidden />
      <div className="min-w-0 flex-1">
        <h2 className="font-bold">{t("track.hold.title")}</h2>
        <p className="mt-1 text-sm font-medium">{pickText(language, onHold.messageEn, onHold.messageHi)}</p>
        <p className="mt-1 text-sm">{pickText(language, onHold.nextEn, onHold.nextHi)}</p>
      </div>
    </section>
  );
}
