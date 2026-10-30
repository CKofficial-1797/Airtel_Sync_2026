import { CheckCircle2, XCircle } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import type { Profile, ResolvedProfile } from "../api/types";
import { maskGstin } from "../lib/mask";

interface Props {
  profile: Profile;
  resolved?: ResolvedProfile | null;
  action?: ReactNode;
}

function Chip({ children, tone = "neutral" }: { children: ReactNode; tone?: "neutral" | "good" | "bad" }) {
  const tones = {
    neutral: "bg-white text-slate-700 border-slate-200",
    good: "bg-green-100 text-green-800 border-green-200",
    bad: "bg-red-100 text-red-800 border-red-200",
  };
  return (
    <li className={`flex items-center gap-1 whitespace-nowrap rounded-full border px-2.5 py-1 text-xs ${tones[tone]}`}>
      {children}
    </li>
  );
}

/** type · city (tier) · staff · sites · GSTIN (masked + status). Shown once facts are known. */
export function ProfileSummary({ profile, resolved, action }: Props) {
  const { t } = useTranslation();
  const tier = resolved?.cityTier ?? profile.cityTier;
  const hasFacts = profile.businessType || profile.city || profile.employees || profile.sites || profile.gstin;
  if (!hasFacts) return null;

  let gst: ReactNode = null;
  if (profile.gstin) {
    const status = resolved?.gstinStatus;
    const masked = maskGstin(profile.gstin);
    if (status === "VERIFIED_ACTIVE") {
      gst = (
        <Chip tone="good">
          <CheckCircle2 className="size-3.5" aria-hidden />
          {masked} · {t("profile.gstActive")}
        </Chip>
      );
    } else if (status === "INACTIVE" || status === "INVALID") {
      gst = (
        <Chip tone="bad">
          <XCircle className="size-3.5" aria-hidden />
          {masked} ·{" "}
          {status === "INACTIVE"
            ? t("profile.gstInactive", { status: resolved?.gstinRegistryStatus ?? "" })
            : t("profile.gstNotVerified")}
        </Chip>
      );
    } else {
      gst = <Chip>{masked}</Chip>;
    }
  } else if (profile.gstinDeclared === true) {
    gst = <Chip>{t("profile.gstDeclared")}</Chip>;
  } else if (profile.gstinDeclared === false) {
    gst = <Chip>{t("profile.noGst")}</Chip>;
  }

  return (
    <div className="flex items-center gap-2 border-b border-slate-200 bg-slate-50 px-3 py-2">
      <ul aria-label={t("profile.label")} className="flex flex-1 gap-1.5 overflow-x-auto">
        {profile.businessType && <Chip>{profile.businessType}</Chip>}
        {(profile.city || tier) && (
          <Chip>
            {profile.city ?? ""}
            {tier ? ` (${t("profile.tier", { tier })})` : ""}
          </Chip>
        )}
        {profile.employees !== undefined && <Chip>{t("profile.staff", { count: profile.employees })}</Chip>}
        {profile.sites !== undefined && <Chip>{t("profile.sites", { count: profile.sites })}</Chip>}
        {gst}
      </ul>
      {action}
    </div>
  );
}
