import { ArrowRight, Info, PhoneCall, Upload } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import type { CheckResult, DocType, KycOverall, KycView } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { formatDate } from "../../lib/format";
import { pickText, slotsForCheck } from "../../lib/kyc";
import { CheckItem } from "./CheckItem";
import { StatusHeader } from "./StatusHeader";

interface Props {
  view: KycView;
  onFix: (slot: DocType) => void;
  onCallback: () => void;
}

/** A short plain-language reason for a rejection; the backend's message below it has the details. */
function rejectionReasonKey(check: CheckResult): string {
  const status = check.evidence?.registryStatus;
  if (check.code === "C06" && (status === "Cancelled" || status === "Suspended")) {
    return `kyc.results.reason.C06${status}`;
  }
  return `kyc.results.reason.${check.code}`;
}

export function ResultsStep({ view, onFix, onCallback }: Props) {
  const { t, i18n } = useTranslation();
  const language = currentLanguage();
  const overall: KycOverall = view.overall ?? "ACTION_REQUIRED";
  const failures = view.checks.filter((c) => c.status === "FAIL");
  const notFixable = failures.filter((c) => !c.fixable);
  const fixableFailures = failures.filter((c) => c.fixable);
  const warnings = view.checks.filter((c) => c.status === "WARN");
  const passed = view.checks.filter((c) => c.status === "PASS").length;
  const correction = view.checks.find((c) => c.code === "C04" && c.status === "PASS" && c.evidence?.correctedTo);

  return (
    <div className="space-y-4">
      {overall === "VERIFIED" && (
        <StatusHeader overall={overall} title={t("kyc.results.verifiedTitle")}>
          {view.estimatedActivationDate && (
            <p className="mt-1 text-sm">{t("kyc.results.activation", { date: formatDate(view.estimatedActivationDate, i18n.language) })}</p>
          )}
          <Link
            to={`/track/${view.orderId}`}
            className="mt-3 flex min-h-12 items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800"
          >
            {t("kyc.results.track")}
            <ArrowRight className="size-4" aria-hidden />
          </Link>
        </StatusHeader>
      )}

      {overall === "ACTION_REQUIRED" && (
        <>
          <StatusHeader overall={overall} title={t("kyc.results.almostThere", { count: fixableFailures.length })}>
            <p className="mt-1 text-sm">{t("kyc.results.almostThereBody")}</p>
          </StatusHeader>
          <section aria-labelledby="to-fix-heading">
            <h2 id="to-fix-heading" className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-600">
              {t("kyc.results.toFix")}
            </h2>
            <ul className="space-y-3">
              {fixableFailures.map((check) => (
                <li key={check.code} className="rounded-2xl border border-amber-300 bg-white p-4">
                  <CheckItem check={check} showHint>
                    <div className="mt-3 flex flex-wrap gap-2">
                      {slotsForCheck(check).map((slot) => (
                        <button
                          key={slot}
                          type="button"
                          onClick={() => onFix(slot)}
                          className="flex min-h-11 items-center gap-2 rounded-xl bg-teal-700 px-3 text-sm font-semibold text-white hover:bg-teal-800"
                        >
                          <Upload className="size-4" aria-hidden />
                          {t(check.code === "C01" ? "kyc.results.upload" : "kyc.results.reupload", { doc: t(`kyc.doc.${slot}.short`) })}
                        </button>
                      ))}
                      {check.code === "C17" && (
                        <Link to="/quote" className="flex min-h-11 items-center rounded-xl border border-slate-300 px-3 text-sm font-semibold text-slate-800 hover:bg-slate-50">
                          {t("kyc.results.changeOrder")}
                        </Link>
                      )}
                    </div>
                  </CheckItem>
                </li>
              ))}
            </ul>
          </section>
        </>
      )}

      {overall === "REJECTED" && (
        <>
          <StatusHeader overall={overall} title={t("kyc.results.rejectedTitle")}>
            {notFixable[0] && <p className="mt-1 text-sm font-medium">{t(rejectionReasonKey(notFixable[0]))}</p>}
          </StatusHeader>
          <section aria-labelledby="why-heading" className="rounded-2xl border border-slate-200 bg-white p-4">
            <h2 id="why-heading" className="mb-2 font-semibold text-slate-900">
              {t("kyc.results.why")}
            </h2>
            <ul className="space-y-3">
              {notFixable.map((check) => (
                <li key={check.code}>
                  <CheckItem check={check} showHint />
                </li>
              ))}
            </ul>
            {fixableFailures.length > 0 && (
              <>
                <h3 className="mb-2 mt-4 text-sm font-semibold text-slate-900">{t("kyc.results.alsoFound")}</h3>
                <ul className="space-y-3">
                  {fixableFailures.map((check) => (
                    <li key={check.code}>
                      <CheckItem check={check} />
                    </li>
                  ))}
                </ul>
              </>
            )}
            <button
              type="button"
              onClick={onCallback}
              className="mt-4 flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800"
            >
              <PhoneCall className="size-4" aria-hidden />
              {t("kyc.results.callback")}
            </button>
          </section>
        </>
      )}

      {correction && (
        <p className="flex items-start gap-2 rounded-2xl border border-teal-200 bg-teal-50 p-3 text-sm text-teal-950" data-testid="ocr-note">
          <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
          <span className="min-w-0 break-words">
            <strong className="block">{t("kyc.results.ocrTitle")}</strong>
            {pickText(language, correction.messageEn, correction.messageHi)}
          </span>
        </p>
      )}

      {warnings.length > 0 && (
        <section aria-labelledby="notes-heading" className="rounded-2xl border border-slate-200 bg-white p-4">
          <h2 id="notes-heading" className="mb-2 font-semibold text-slate-900">
            {t("kyc.results.notes")}
          </h2>
          <ul className="space-y-3">
            {warnings.map((check) => (
              <li key={check.code}>
                <CheckItem check={check} showHint />
              </li>
            ))}
          </ul>
        </section>
      )}

      <details className="rounded-2xl border border-slate-200 bg-white px-4">
        <summary className="flex min-h-12 cursor-pointer items-center font-semibold text-slate-900">
          {t("kyc.results.allChecks", { count: passed })}
        </summary>
        <ul className="space-y-3 pb-4">
          {view.checks.map((check) => (
            <li key={check.code}>
              <CheckItem check={check} />
            </li>
          ))}
        </ul>
      </details>
    </div>
  );
}
