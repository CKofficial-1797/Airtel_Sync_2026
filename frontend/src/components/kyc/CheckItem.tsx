import { CircleAlert, CircleCheck, CircleMinus, CircleX, type LucideIcon } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { CheckResult, CheckStatus } from "../../api/types";
import { currentLanguage } from "../../i18n";
import { pickText } from "../../lib/kyc";

const STATUS: Record<CheckStatus, { icon: LucideIcon; color: string }> = {
  PASS: { icon: CircleCheck, color: "text-green-800" },
  WARN: { icon: CircleAlert, color: "text-amber-800" },
  FAIL: { icon: CircleX, color: "text-red-700" },
  SKIPPED: { icon: CircleMinus, color: "text-slate-600" },
};

interface Props {
  check: CheckResult;
  /** Show the backend's fix hint under the message. */
  showHint?: boolean;
  children?: ReactNode;
}

/** One check: icon AND a word for its status (never colour alone), message in the UI language, optional actions. */
export function CheckItem({ check, showHint, children }: Props) {
  const { t } = useTranslation();
  const language = currentLanguage();
  const { icon: Icon, color } = STATUS[check.status];
  const hint = pickText(language, check.fixHintEn, check.fixHintHi);
  return (
    <div className="flex items-start gap-2 text-sm" data-testid={`check-${check.code}`}>
      <Icon className={`mt-0.5 size-5 shrink-0 ${color}`} aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="break-words text-slate-800">
          <span className={`font-semibold ${color}`}>{t(`kyc.results.status.${check.status}`)}: </span>
          {pickText(language, check.messageEn, check.messageHi)}
        </p>
        {showHint && hint && <p className="mt-1 break-words font-medium text-slate-900">{hint}</p>}
        {children}
      </div>
    </div>
  );
}
