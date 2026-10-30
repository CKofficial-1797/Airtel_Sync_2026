import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router-dom";
import type { DocType } from "../api/types";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { ErrorBanner } from "../components/ErrorBanner";
import { ConsentStep } from "../components/kyc/ConsentStep";
import { JourneyStepper } from "../components/kyc/JourneyStepper";
import { ResultsStep } from "../components/kyc/ResultsStep";
import { UploadStep } from "../components/kyc/UploadStep";
import { LanguageToggle } from "../components/LanguageToggle";
import { Header } from "../components/Layout";
import { Toast } from "../components/Toast";
import { useKyc } from "../hooks/useKyc";
import { stepFor } from "../lib/kyc";

/** Once installed the records back a live connection; the notice points to the Grievance Officer instead. */
const NO_WITHDRAWAL = ["INSTALLED", "ACTIVE"];

function Skeleton() {
  const { t } = useTranslation();
  return (
    <div className="space-y-3 px-3 py-4" role="status" aria-label={t("common.loading")}>
      {[0, 1, 2].map((i) => (
        <div key={i} className="h-28 animate-pulse rounded-2xl bg-slate-200" />
      ))}
    </div>
  );
}

/** /kyc/:orderId: consent → upload → results, chosen from the server's state on every load. */
export function Kyc() {
  const { t } = useTranslation();
  const { orderId = "" } = useParams();
  const kyc = useKyc(orderId);
  const { view } = kyc;
  // Set by a "Re-upload" button: shows the upload step for that slot while the verdict still stands.
  const [fixSlot, setFixSlot] = useState<DocType | null>(null);
  const [confirmWithdraw, setConfirmWithdraw] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const dismissToast = useCallback(() => setToast(null), []);

  const serverStep = view ? stepFor(view) : null;
  const step = serverStep === "results" && fixSlot ? "upload" : serverStep;

  useEffect(() => {
    if (step && !fixSlot) window.scrollTo(0, 0);
  }, [step, fixSlot]);

  const submit = () => {
    setFixSlot(null);
    void kyc.submit();
  };

  const withdraw = async () => {
    setConfirmWithdraw(false);
    setFixSlot(null);
    try {
      await kyc.withdraw();
      setToast(t("kyc.withdraw.done"));
    } catch {
      setToast(t("kyc.withdraw.failed"));
    }
  };

  return (
    <>
      <Header title={t("kyc.title")} backTo="/" actions={<LanguageToggle />} />
      <JourneyStepper current="kyc" />
      <main id="main" className="flex-1 px-3 py-4">
        {kyc.loadError === "notFound" && (
          <div className="py-8 text-center">
            <p role="alert" className="text-red-700">
              {t("kyc.notFound")}
            </p>
            <Link to="/" className="mt-3 inline-flex min-h-11 items-center font-medium text-teal-800 underline">
              {t("notFound.home")}
            </Link>
          </div>
        )}
        {kyc.loadError && kyc.loadError !== "notFound" && !view && (
          <div className="py-8">
            <ErrorBanner
              message={kyc.loadError === "network" ? t("common.networkError") : t("common.genericError")}
              onRetry={() => void kyc.reload()}
            />
          </div>
        )}
        {!view && !kyc.loadError && <Skeleton />}

        {view && (
          <>
            <p className="mb-3 text-sm text-slate-600">
              {t("kyc.orderRef")}: <span className="font-mono font-semibold text-slate-900">{view.orderRef}</span>
            </p>
            {view.orderStatus === "CANCELLED" && (
              <p role="status" className="rounded-2xl border border-slate-200 bg-white p-4 text-slate-800">
                {t("kyc.cancelled")}{" "}
                <Link to={`/track/${view.orderId}`} className="font-medium text-teal-800 underline">
                  {t("kyc.results.track")}
                </Link>
              </p>
            )}
            {view.orderStatus !== "CANCELLED" && step === "consent" && <ConsentStep onAgree={kyc.consent} />}
            {view.orderStatus !== "CANCELLED" && step === "upload" && (
              <UploadStep
                view={view}
                focusSlot={fixSlot}
                submitting={kyc.submitting}
                submitError={kyc.submitError}
                onUpload={kyc.upload}
                onSubmit={submit}
                onBackToResults={serverStep === "results" ? () => setFixSlot(null) : undefined}
              />
            )}
            {step === "results" && <ResultsStep view={view} onFix={setFixSlot} onCallback={() => setToast(t("kyc.results.callbackDone"))} />}

            {view.consent.active && !NO_WITHDRAWAL.includes(view.orderStatus) && (
              <footer className="mt-6 border-t border-slate-200 pt-2 text-center">
                <button
                  type="button"
                  onClick={() => setConfirmWithdraw(true)}
                  className="min-h-11 px-2 text-sm font-medium text-slate-700 underline"
                >
                  {t("kyc.withdraw.link")}
                </button>
              </footer>
            )}
          </>
        )}
      </main>

      <ConfirmDialog
        open={confirmWithdraw}
        title={t("kyc.withdraw.title")}
        body={t("kyc.withdraw.body")}
        confirmLabel={t("kyc.withdraw.confirm")}
        onConfirm={() => void withdraw()}
        onCancel={() => setConfirmWithdraw(false)}
      />
      {toast && <Toast message={toast} onDismiss={dismissToast} />}
    </>
  );
}
