import { Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, NetworkError } from "../../api/client";
import { bookInstallSlot, getInstallSlots } from "../../api/track";
import type { InstallSlotName, InstallSlots, TrackerView } from "../../api/types";
import { ErrorBanner } from "../ErrorBanner";

interface Props {
  orderId: string;
  onBooked: (view: TrackerView) => void;
  onCancel?: () => void;
}

function parts(iso: string, language: string): { weekday: string; day: string } {
  const [y, m, d] = iso.split("-").map(Number);
  const date = new Date(y ?? 0, (m ?? 1) - 1, d ?? 1);
  const locale = language === "hi" ? "hi-IN" : "en-IN";
  return {
    weekday: new Intl.DateTimeFormat(locale, { weekday: "short" }).format(date),
    day: new Intl.DateTimeFormat(locale, { day: "numeric", month: "short" }).format(date),
  };
}

const chip = "min-h-11 rounded-xl border text-sm font-medium disabled:cursor-not-allowed";
const chipOn = "border-teal-700 bg-teal-700 text-white";
const chipOff = "border-slate-300 bg-white text-slate-800 hover:bg-slate-50 disabled:border-slate-200 disabled:bg-slate-100 disabled:text-slate-500 disabled:line-through";

/** Self-scheduling: only dates the server offers can be picked, so a date before the ETA or a Sunday isn't possible. */
export function SlotPicker({ orderId, onBooked, onCancel }: Props) {
  const { t, i18n } = useTranslation();
  const [options, setOptions] = useState<InstallSlots | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [date, setDate] = useState<string | null>(null);
  const [slot, setSlot] = useState<InstallSlotName | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoadFailed(false);
    getInstallSlots(orderId)
      .then((o) => {
        if (cancelled) return;
        setOptions(o);
        setDate(o.current?.date ?? null);
        setSlot(o.current?.slot ?? null);
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [orderId, attempt]);

  if (loadFailed) {
    return <ErrorBanner message={t("common.networkError")} onRetry={() => setAttempt((n) => n + 1)} />;
  }
  if (!options) {
    return (
      <p className="flex items-center gap-2 text-sm text-slate-600" role="status">
        <Loader2 className="size-4 animate-spin" aria-hidden />
        {t("common.loading")}
      </p>
    );
  }

  const confirm = async () => {
    if (!date || !slot) return;
    setSaving(true);
    setError(null);
    try {
      onBooked(await bookInstallSlot(orderId, date, slot));
    } catch (e) {
      if (e instanceof ApiError && e.code === "INVALID_SLOT") setError(t("track.slot.invalid"));
      else setError(e instanceof NetworkError ? t("common.networkError") : t("common.genericError"));
      setSaving(false);
    }
  };

  return (
    <div className="space-y-3">
      <fieldset>
        <legend className="mb-1 text-sm font-medium text-slate-900">{t("track.slot.date")}</legend>
        <div className="grid grid-cols-4 gap-2">
          {options.dates.map((d) => {
            const { weekday, day } = parts(d.date, i18n.language);
            return (
              <button
                key={d.date}
                type="button"
                disabled={!d.available}
                aria-pressed={date === d.date}
                onClick={() => setDate(d.date)}
                className={`${chip} px-1 py-1 leading-tight ${date === d.date ? chipOn : chipOff}`}
                data-testid={`date-${d.date}`}
              >
                <span className="block text-xs font-normal">{weekday}</span>
                {day}
              </button>
            );
          })}
        </div>
        <p className="mt-1 text-xs text-slate-600">{t("track.slot.noSundays")}</p>
      </fieldset>

      <fieldset>
        <legend className="mb-1 text-sm font-medium text-slate-900">{t("track.slot.time")}</legend>
        <div className="grid grid-cols-3 gap-2">
          {options.slots.map((s) => (
            <button
              key={s}
              type="button"
              aria-pressed={slot === s}
              onClick={() => setSlot(s)}
              className={`${chip} ${slot === s ? chipOn : chipOff}`}
            >
              {t(`track.slot.${s}`)}
            </button>
          ))}
        </div>
      </fieldset>

      {error && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}

      <div className="flex gap-2">
        {onCancel && (
          <button type="button" onClick={onCancel} className="min-h-12 rounded-xl px-4 font-medium text-slate-700 hover:bg-slate-100">
            {t("common.cancel")}
          </button>
        )}
        <button
          type="button"
          onClick={() => void confirm()}
          disabled={!date || !slot || saving}
          className="flex min-h-12 flex-1 items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800 disabled:opacity-60"
        >
          {saving && <Loader2 className="size-4 animate-spin" aria-hidden />}
          {t("track.slot.confirm")}
        </button>
      </div>
    </div>
  );
}
