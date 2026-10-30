import { ArrowRight, CalendarCheck, CircleCheck, Info } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import type { TrackerView } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { formatDate } from "../../lib/format";
import { pickText } from "../../lib/kyc";
import { SlotPicker } from "./SlotPicker";

interface Props {
  view: TrackerView;
  onBooked: (view: TrackerView) => void;
}

/** The one thing to do next, or a clear "nothing to do". Driven entirely by the server's nextAction. */
export function NextActionCard({ view, onBooked }: Props) {
  const { t, i18n } = useTranslation();
  const language = currentLanguage();
  const [rescheduling, setRescheduling] = useState(false);
  const { nextAction, blocked, onHold, installSlot } = view;
  const label = pickText(language, nextAction.labelEn, nextAction.labelHi);
  const scheduled = view.currentStatus === "INSTALL_SCHEDULED" && installSlot;

  const booked = (next: TrackerView) => {
    setRescheduling(false);
    onBooked(next);
  };

  return (
    <div className="space-y-3">
      {blocked && (
        <section className="rounded-2xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950" data-testid="blocked-card">
          <p className="flex items-start gap-2 font-semibold">
            <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
            <span className="min-w-0 break-words">{pickText(language, blocked.messageEn, blocked.messageHi)}</span>
          </p>
          <p className="mt-1 break-words">{pickText(language, blocked.helpEn, blocked.helpHi)}</p>
        </section>
      )}

      {nextAction.type === "PICK_SLOT" && (
        <section aria-labelledby="pick-slot-heading" className="rounded-2xl border border-teal-300 bg-white p-4" data-testid="slot-card">
          <h2 id="pick-slot-heading" className="mb-3 font-semibold text-slate-900">{label}</h2>
          <SlotPicker orderId={view.orderId} onBooked={booked} />
        </section>
      )}

      {nextAction.link && (
        <Link
          to={nextAction.link}
          className="flex min-h-12 items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800"
          data-testid="next-action-link"
        >
          {label}
          <ArrowRight className="size-4" aria-hidden />
        </Link>
      )}

      {scheduled && (
        <section className="rounded-2xl border border-slate-200 bg-white p-4" data-testid="booked-card">
          <h2 className="flex items-center gap-2 font-semibold text-slate-900">
            <CalendarCheck className="size-5 text-teal-700" aria-hidden />
            {t("track.slot.booked")}
          </h2>
          <p className="mt-1 text-slate-800">
            {formatDate(installSlot.date, i18n.language)} · {t(`track.slot.${installSlot.slot}`)}
          </p>
          {rescheduling ? (
            <div className="mt-3">
              <SlotPicker orderId={view.orderId} onBooked={booked} onCancel={() => setRescheduling(false)} />
            </div>
          ) : (
            <button type="button" onClick={() => setRescheduling(true)} className="mt-1 min-h-11 text-sm font-medium text-teal-800 underline">
              {t("track.slot.change")}
            </button>
          )}
        </section>
      )}

      {nextAction.type === "NONE" && !blocked && !onHold && (
        <p className="flex items-start gap-2 rounded-2xl bg-teal-50 p-3 text-sm text-teal-950" data-testid="reassurance">
          <CircleCheck className="mt-0.5 size-4 shrink-0" aria-hidden />
          {label}
        </p>
      )}
    </div>
  );
}
