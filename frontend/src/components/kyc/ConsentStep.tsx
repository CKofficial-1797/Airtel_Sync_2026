import { Loader2, ShieldCheck } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { isAbort, NetworkError } from "../../api/client";
import { getConsentNotice } from "../../api/kyc";
import type { ConsentNotice, ConsentRequest } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { ErrorBanner } from "../ErrorBanner";

interface Props {
  onAgree: (body: ConsentRequest) => Promise<void>;
}

/** DPDP consent: plain-language summary, one unticked box per purpose, the full notice on demand. */
export function ConsentStep({ onAgree }: Props) {
  const { t } = useTranslation();
  const language = currentLanguage();
  const [notice, setNotice] = useState<ConsentNotice | null>(null);
  const [noticeFailed, setNoticeFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [ticked, setTicked] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setNoticeFailed(false);
    getConsentNotice(language, controller.signal)
      .then(setNotice)
      .catch((e: unknown) => {
        if (!isAbort(e)) setNoticeFailed(true);
      });
    return () => controller.abort();
  }, [language, attempt]);

  if (noticeFailed && !notice) {
    return (
      <div className="py-8">
        <ErrorBanner message={t("common.networkError")} onRetry={() => setAttempt((n) => n + 1)} />
      </div>
    );
  }
  if (!notice) {
    return (
      <div className="flex items-center justify-center gap-2 py-12 text-slate-600" role="status">
        <Loader2 className="size-5 animate-spin" aria-hidden />
        {t("common.loading")}
      </div>
    );
  }

  const canAgree = notice.purposes.filter((p) => p.required).every((p) => ticked[p.code]);

  const agree = async () => {
    setSaving(true);
    setError(null);
    try {
      await onAgree({
        noticeVersion: notice.version,
        language: notice.language,
        purposes: Object.fromEntries(notice.purposes.map((p) => [p.code, Boolean(ticked[p.code])])),
      });
    } catch (e) {
      setError(e instanceof NetworkError ? t("common.networkError") : t("common.genericError"));
      setSaving(false);
    }
  };

  const summary = [
    ["whatTitle", t("kyc.consent.whatBody")],
    ["keepTitle", t("kyc.consent.keepBody")],
    ["howLongTitle", t("kyc.consent.howLongBody", { days: notice.retentionDays })],
    ["rightsTitle", t("kyc.consent.rightsBody")],
    ["contactTitle", notice.contact],
  ];

  return (
    <div className="space-y-4">
      <section className="rounded-2xl border border-slate-200 bg-white p-4" aria-labelledby="consent-heading">
        <h2 id="consent-heading" className="flex items-center gap-2 font-semibold text-slate-900">
          <ShieldCheck className="size-5 text-teal-700" aria-hidden />
          {t("kyc.consent.heading")}
        </h2>
        <dl className="mt-3 space-y-3 text-sm">
          {summary.map(([title, body]) => (
            <div key={title}>
              <dt className="font-medium text-slate-900">{t(`kyc.consent.${title}`)}</dt>
              <dd className="text-slate-700">{body}</dd>
            </div>
          ))}
        </dl>
        <details className="mt-3 text-sm">
          <summary className="flex min-h-11 cursor-pointer items-center font-medium text-teal-800 underline">{t("kyc.consent.readFull")}</summary>
          <div className="space-y-2 text-slate-700">
            <p>
              <strong>{notice.title}</strong> · {notice.dataFiduciary}
            </p>
            <p>{notice.dataCollected}</p>
            <p>{notice.retention}</p>
            <ul className="list-disc space-y-1 pl-5">
              {notice.rights.map((right) => (
                <li key={right}>{right}</li>
              ))}
            </ul>
            <p className="text-xs text-slate-600">{t("kyc.consent.noticeVersion", { version: notice.version })}</p>
          </div>
        </details>
      </section>

      <fieldset className="rounded-2xl border border-slate-200 bg-white p-4">
        <legend className="px-1 font-semibold text-slate-900">{t("kyc.consent.purposesTitle")}</legend>
        <div className="mt-1 space-y-1">
          {notice.purposes.map((p) => (
            <label key={p.code} className="flex min-h-11 cursor-pointer items-start gap-3 rounded-xl p-2 hover:bg-slate-50">
              <input
                type="checkbox"
                checked={Boolean(ticked[p.code])}
                onChange={(e) => setTicked((s) => ({ ...s, [p.code]: e.target.checked }))}
                className="mt-1 size-5 shrink-0 accent-teal-700"
              />
              <span className="text-sm">
                <span className="font-medium text-slate-900">{p.title}</span>{" "}
                <span className={`rounded px-1.5 py-0.5 text-xs font-medium ${p.required ? "bg-teal-100 text-teal-900" : "bg-slate-100 text-slate-700"}`}>
                  {t(p.required ? "kyc.consent.required" : "kyc.consent.optional")}
                </span>
                <span className="mt-0.5 block text-slate-700">{p.description}</span>
              </span>
            </label>
          ))}
        </div>
      </fieldset>

      {error && <ErrorBanner message={error} onDismiss={() => setError(null)} />}

      <div>
        <button
          type="button"
          onClick={() => void agree()}
          disabled={!canAgree || saving}
          className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800 disabled:opacity-60"
        >
          {saving && <Loader2 className="size-4 animate-spin" aria-hidden />}
          {t("kyc.consent.agree")}
        </button>
        {!canAgree && <p className="mt-2 text-center text-sm text-slate-600">{t("kyc.consent.requiredHint")}</p>}
      </div>
    </div>
  );
}
