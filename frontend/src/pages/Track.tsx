import { Copy, Loader2, RotateCw } from "lucide-react";
import { useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";
import { ErrorBanner } from "../components/ErrorBanner";
import { JourneyStepper } from "../components/kyc/JourneyStepper";
import { LanguageToggle } from "../components/LanguageToggle";
import { Header } from "../components/Layout";
import { Toast } from "../components/Toast";
import { ActivityList } from "../components/track/ActivityList";
import { DemoPanel } from "../components/track/DemoPanel";
import { EtaCard } from "../components/track/EtaCard";
import { HelpSection } from "../components/track/HelpSection";
import { HoldBanner } from "../components/track/HoldBanner";
import { NextActionCard } from "../components/track/NextActionCard";
import { Timeline } from "../components/track/Timeline";
import { useTracker } from "../hooks/useTracker";
import { formatInr } from "../lib/format";

function Skeleton() {
  const { t } = useTranslation();
  return (
    <div className="space-y-3" role="status" aria-label={t("common.loading")}>
      {[0, 1, 2].map((i) => (
        <div key={i} className="h-28 animate-pulse rounded-2xl bg-slate-200" />
      ))}
    </div>
  );
}

/** /track/:orderId: everything an owner would otherwise call support to ask. */
export function Track() {
  const { t, i18n } = useTranslation();
  const { orderId = "" } = useParams();
  const tracker = useTracker(orderId);
  const { view } = tracker;
  const [toast, setToast] = useState<string | null>(null);
  const dismissToast = useCallback(() => setToast(null), []);

  const copyRef = async () => {
    try {
      await navigator.clipboard.writeText(view?.orderRef ?? "");
      setToast(t("track.copied"));
    } catch {
      setToast(t("track.copyFailed"));
    }
  };

  const checkedAt = tracker.checkedAt
    ? new Intl.DateTimeFormat(i18n.language === "hi" ? "hi-IN" : "en-IN", { hour: "numeric", minute: "2-digit" }).format(tracker.checkedAt)
    : "";

  return (
    <>
      <Header title={t("track.title")} backTo="/" actions={<LanguageToggle />} />
      <JourneyStepper current="track" />
      <main id="main" className="flex-1 space-y-4 px-3 py-4">
        {tracker.error === "notFound" && (
          <div className="py-8 text-center">
            <p role="alert" className="text-red-700">{t("track.notFound")}</p>
            <Link to="/track" className="mt-3 inline-flex min-h-11 items-center font-medium text-teal-800 underline">
              {t("track.findOrder")}
            </Link>
          </div>
        )}
        {tracker.error && tracker.error !== "notFound" && (
          <ErrorBanner
            message={tracker.error === "network" ? t("common.networkError") : t("common.genericError")}
            onRetry={() => void tracker.refresh()}
            busy={tracker.refreshing}
          />
        )}
        {!view && !tracker.error && <Skeleton />}

        {view && (
          <>
            <section className="rounded-2xl border border-slate-200 bg-white p-4" data-testid="tracker-header">
              {view.businessName && <h2 className="break-words text-lg font-bold text-slate-900">{view.businessName}</h2>}
              <p className="flex items-center gap-1 text-sm text-slate-600">
                {t("kyc.orderRef")}: <span className="font-mono font-semibold text-slate-900">{view.orderRef}</span>
                <button
                  type="button"
                  onClick={() => void copyRef()}
                  className="flex size-11 items-center justify-center rounded-lg text-slate-700 hover:bg-slate-100"
                  aria-label={t("track.copy")}
                >
                  <Copy className="size-4" aria-hidden />
                </button>
              </p>
              <p className="text-sm text-slate-600">
                <span className="text-base font-semibold text-slate-900 tabular-nums">{formatInr(view.monthlyTotalInr)}</span>
                {t("card.perMonth")}
              </p>
            </section>

            {view.onHold && <HoldBanner onHold={view.onHold} />}
            <NextActionCard view={view} onBooked={tracker.apply} />
            <EtaCard view={view} />
            <Timeline steps={view.steps} onHold={view.onHold !== null} />
            <ActivityList events={view.events} />

            <div className="flex items-center justify-between gap-2 text-sm text-slate-600">
              <span aria-live="polite">{t("track.lastUpdated", { time: checkedAt })}</span>
              <button
                type="button"
                onClick={() => void tracker.refresh()}
                disabled={tracker.refreshing}
                className="flex min-h-11 items-center gap-1 rounded-lg px-2 font-medium text-teal-800 hover:bg-teal-50 disabled:opacity-60"
              >
                {tracker.refreshing ? <Loader2 className="size-4 animate-spin" aria-hidden /> : <RotateCw className="size-4" aria-hidden />}
                {t("track.refresh")}
              </button>
            </div>

            <HelpSection onCallback={() => setToast(t("kyc.results.callbackDone"))} />
            <DemoPanel view={view} onResult={tracker.apply} />
          </>
        )}
      </main>
      {toast && <Toast message={toast} onDismiss={dismissToast} />}
    </>
  );
}
