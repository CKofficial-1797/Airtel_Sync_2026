import { ArrowLeft, Loader2, Lock } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { DocType, KycView } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { DOC_TYPES, pickText, unreadSlots } from "../../lib/kyc";
import { ConfirmDialog } from "../ConfirmDialog";
import { ErrorBanner } from "../ErrorBanner";
import { UploadSlot } from "./UploadSlot";

interface Props {
  view: KycView;
  focusSlot: DocType | null;
  submitting: boolean;
  submitError: "network" | "generic" | null;
  onUpload: (docType: DocType, file: File) => Promise<void>;
  onSubmit: () => void;
  onBackToResults?: () => void;
}

export function UploadStep({ view, focusSlot, submitting, submitError, onUpload, onSubmit, onBackToResults }: Props) {
  const { t } = useTranslation();
  const language = currentLanguage();
  const [busySlots, setBusySlots] = useState<DocType[]>([]);
  const [confirming, setConfirming] = useState(false);

  const onBusyChange = useCallback((docType: DocType, busy: boolean) => {
    setBusySlots((s) => (busy ? [...s, docType] : s.filter((d) => d !== docType)));
  }, []);

  // Coming from a "Re-upload" button on the results: bring that slot into view.
  useEffect(() => {
    if (!focusSlot) return;
    const slot = document.getElementById(`slot-${focusSlot}`);
    slot?.scrollIntoView?.({ block: "center" });
    slot?.focus({ preventScroll: true });
  }, [focusSlot]);

  const unread = unreadSlots(view);
  const anyBusy = busySlots.length > 0;
  const lastFailures = view.overall ? view.checks.filter((c) => c.status === "FAIL") : [];
  const missingNames = unread.map((d) => t(`kyc.doc.${d}.short`)).join(", ");
  const checkLabel = view.overall ? t("kyc.upload.checkAgain") : t("kyc.upload.check");

  return (
    <div className="space-y-4">
      {onBackToResults && (
        <button type="button" onClick={onBackToResults} className="flex min-h-11 items-center gap-1 text-sm font-medium text-teal-800 underline">
          <ArrowLeft className="size-4" aria-hidden />
          {t("kyc.upload.backToResults")}
        </button>
      )}

      {lastFailures.length > 0 && (
        <section className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-950" aria-labelledby="last-check-heading">
          <h2 id="last-check-heading" className="font-semibold">
            {t("kyc.upload.lastCheck")}
          </h2>
          <ul className="mt-1 list-disc space-y-1 pl-5">
            {lastFailures.map((c) => (
              <li key={c.code}>{pickText(language, c.messageEn, c.messageHi)}</li>
            ))}
          </ul>
        </section>
      )}

      <h2 className="font-semibold text-slate-900">{t("kyc.upload.heading")}</h2>
      <ul className="space-y-3">
        {DOC_TYPES.map((docType) => (
          <UploadSlot
            key={docType}
            docType={docType}
            doc={view.documents.find((d) => d.docType === docType)}
            highlighted={focusSlot === docType}
            onUpload={onUpload}
            onBusyChange={onBusyChange}
          />
        ))}
      </ul>

      <p className="flex items-center justify-center gap-1.5 text-center text-sm text-slate-600">
        <Lock className="size-4 shrink-0" aria-hidden />
        {t("kyc.upload.privacy")}
      </p>

      {submitError && (
        <ErrorBanner
          message={submitError === "network" ? t("common.networkError") : t("common.genericError")}
          onRetry={onSubmit}
          busy={submitting}
        />
      )}

      <div>
        <button
          type="button"
          onClick={onSubmit}
          disabled={unread.length > 0 || anyBusy || submitting}
          className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800 disabled:opacity-60"
        >
          {submitting && <Loader2 className="size-4 animate-spin" aria-hidden />}
          {submitting ? t("kyc.upload.checking") : checkLabel}
        </button>
        {unread.length > 0 && (
          <button
            type="button"
            onClick={() => setConfirming(true)}
            disabled={anyBusy || submitting}
            className="mt-1 min-h-11 w-full text-sm font-medium text-teal-800 underline disabled:opacity-60"
          >
            {t("kyc.upload.noAll")}
          </button>
        )}
      </div>

      <ConfirmDialog
        open={confirming}
        title={t("kyc.upload.noAllTitle")}
        body={t("kyc.upload.noAllBody", { docs: missingNames })}
        confirmLabel={t("kyc.upload.noAllConfirm")}
        onConfirm={() => {
          setConfirming(false);
          onSubmit();
        }}
        onCancel={() => setConfirming(false)}
      />
    </div>
  );
}
