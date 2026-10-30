import { Building2, Camera, Circle, CircleCheck, CircleX, FileText, IdCard, ImagePlus, Loader2, RotateCw, Zap, type LucideIcon } from "lucide-react";
import { useEffect, useState, type ChangeEvent } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, ConsentRequiredError, ExtractionUnavailableError, NetworkError } from "../../api/client";
import type { DocType, DocumentView } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { ImageRejected, prepareImage } from "../../lib/image";
import { pickText, readSummary } from "../../lib/kyc";

const ICONS: Record<DocType, LucideIcon> = {
  GST_CERTIFICATE: FileText,
  UDYAM_CERTIFICATE: Building2,
  SIGNATORY_ID: IdCard,
  ADDRESS_PROOF: Zap,
};

const ACCEPT = "image/png,image/jpeg";

interface Props {
  docType: DocType;
  doc?: DocumentView;
  highlighted?: boolean;
  onUpload: (docType: DocType, file: File) => Promise<void>;
  onBusyChange?: (docType: DocType, busy: boolean) => void;
}

/** Why an upload attempt failed, and whether the same photo can simply be sent again. */
function describeFailure(e: unknown): { key: string; retryable: boolean } {
  if (e instanceof ImageRejected) return { key: `kyc.upload.errors.${e.problem}`, retryable: false };
  if (e instanceof NetworkError) return { key: "common.networkError", retryable: true };
  if (e instanceof ExtractionUnavailableError) return { key: "kyc.upload.errors.busy", retryable: true };
  if (e instanceof ApiError && e.code === "FILE_TOO_LARGE") return { key: "kyc.upload.errors.size", retryable: false };
  if (e instanceof ApiError && (e.code === "UNSUPPORTED_FILE_TYPE" || e.code === "INVALID_IMAGE" || e.code === "EMPTY_FILE")) {
    return { key: "kyc.upload.errors.type", retryable: false };
  }
  return { key: "common.genericError", retryable: true };
}

/**
 * One document slot. The owner can't edit what was read (integrity): the only fix is another photo.
 * A failed attempt leaves the last saved document in place; only a new successful read replaces it.
 */
export function UploadSlot({ docType, doc, highlighted, onUpload, onBusyChange }: Props) {
  const { t } = useTranslation();
  const language = currentLanguage();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [retryFile, setRetryFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string | null>(null);

  useEffect(
    () => () => {
      if (preview) URL.revokeObjectURL(preview);
    },
    [preview],
  );

  const send = async (file: File) => {
    setBusy(true);
    onBusyChange?.(docType, true);
    setFailure(null);
    setRetryFile(null);
    try {
      const prepared = await prepareImage(file);
      if (typeof URL.createObjectURL === "function") setPreview(URL.createObjectURL(prepared));
      await onUpload(docType, prepared);
    } catch (e) {
      if (!(e instanceof ConsentRequiredError)) {
        const { key, retryable } = describeFailure(e);
        setFailure(t(key));
        setRetryFile(retryable ? file : null);
      }
    } finally {
      setBusy(false);
      onBusyChange?.(docType, false);
    }
  };

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ""; // so picking the same file again still fires change
    if (file) void send(file);
  };

  const Icon = ICONS[docType];
  const title = t(`kyc.doc.${docType}.title`);
  const summary = doc ? readSummary(doc) : null;
  const hint = doc && doc.status !== "OK" ? pickText(language, doc.fixHintEn, doc.fixHintHi) : null;
  const failedRead = doc?.status === "UNREADABLE" || doc?.status === "WRONG_DOC_TYPE";

  let statusIcon = <Circle className="size-4 text-slate-500" aria-hidden />;
  let statusText = t("kyc.upload.status.empty");
  let statusClass = "text-slate-600";
  if (busy) {
    statusIcon = <Loader2 className="size-4 animate-spin" aria-hidden />;
    statusText = t("kyc.upload.status.uploading");
    statusClass = "text-teal-800";
  } else if (doc?.status === "OK") {
    statusIcon = <CircleCheck className="size-4" aria-hidden />;
    statusText = t("kyc.upload.status.read");
    statusClass = "text-green-800";
  } else if (doc?.status === "UNREADABLE") {
    statusIcon = <CircleX className="size-4" aria-hidden />;
    statusText = t("kyc.upload.status.unreadable");
    statusClass = "text-red-700";
  } else if (doc?.status === "WRONG_DOC_TYPE") {
    statusIcon = <CircleX className="size-4" aria-hidden />;
    statusText = t("kyc.upload.status.wrong");
    statusClass = "text-red-700";
  }

  const pickerClass =
    "flex min-h-11 cursor-pointer items-center justify-center gap-2 rounded-xl px-3 text-sm font-medium focus-within:outline-3 focus-within:outline-offset-2 focus-within:outline-teal-700";

  return (
    <li
      id={`slot-${docType}`}
      tabIndex={-1}
      className={`rounded-2xl border bg-white p-4 ${failedRead ? "border-red-300" : "border-slate-200"} ${highlighted ? "ring-2 ring-teal-700" : ""}`}
    >
      <div className="flex items-start gap-3">
        {preview ? (
          <img src={preview} alt={t("kyc.upload.previewAlt", { doc: title })} className="size-12 shrink-0 rounded-lg border border-slate-200 object-cover" />
        ) : (
          <span className="flex size-12 shrink-0 items-center justify-center rounded-lg bg-slate-100 text-slate-600">
            <Icon className="size-6" aria-hidden />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <h3 className="font-semibold text-slate-900">{title}</h3>
          <p className="text-sm text-slate-600">{t(`kyc.doc.${docType}.hint`)}</p>
          <p className={`mt-1 flex items-center gap-1 text-sm font-medium ${statusClass}`} aria-live="polite">
            {statusIcon}
            {statusText}
          </p>
        </div>
      </div>

      {summary && !busy && (
        <p className="mt-2 break-words rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-800" data-testid={`summary-${docType}`}>
          {summary.id ? (
            <>
              {t(`kyc.upload.summary.${summary.idLabel}`)} <span className="font-mono">{summary.id}</span>
            </>
          ) : (
            <span className="font-medium text-amber-900">{t(`kyc.upload.summary.${summary.idLabel}Blank`)}</span>
          )}
          {summary.name && ` · ${summary.name}`}
        </p>
      )}

      {failedRead && hint && !busy && (
        <p role="alert" className="mt-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
          {hint}
        </p>
      )}

      {failure && (
        <p role="alert" className="mt-2 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
          {failure}
        </p>
      )}

      <div className="mt-3 flex flex-wrap gap-2">
        {retryFile && (
          <button
            type="button"
            onClick={() => void send(retryFile)}
            disabled={busy}
            className="flex min-h-11 items-center gap-2 rounded-xl bg-red-700 px-3 text-sm font-medium text-white hover:bg-red-800 disabled:opacity-60"
          >
            <RotateCw className="size-4" aria-hidden />
            {t("common.retry")}
          </button>
        )}
        <label className={`${pickerClass} flex-1 bg-teal-700 text-white hover:bg-teal-800 ${busy ? "pointer-events-none opacity-60" : ""}`}>
          <input type="file" accept={ACCEPT} capture="environment" onChange={onPick} disabled={busy} className="sr-only" />
          <Camera className="size-4" aria-hidden />
          {t(doc ? "kyc.upload.retake" : "kyc.upload.takePhoto")}
          <span className="sr-only">: {title}</span>
        </label>
        <label className={`${pickerClass} flex-1 border border-slate-300 text-slate-800 hover:bg-slate-50 ${busy ? "pointer-events-none opacity-60" : ""}`}>
          <input type="file" accept={ACCEPT} onChange={onPick} disabled={busy} className="sr-only" data-testid={`file-${docType}`} />
          <ImagePlus className="size-4" aria-hidden />
          {t("kyc.upload.choose")}
          <span className="sr-only">: {title}</span>
        </label>
      </div>
    </li>
  );
}
