import { Flame, Snowflake, Thermometer } from "lucide-react";
import { useTranslation } from "react-i18next";

const COLD = { className: "bg-sky-100 text-sky-900", Icon: Snowflake };
const STYLES: Record<string, typeof COLD> = {
  HOT: { className: "bg-red-100 text-red-800", Icon: Flame },
  WARM: { className: "bg-amber-100 text-amber-900", Icon: Thermometer },
  COLD,
};

/** The band is always written out and has its own icon, so it never depends on colour alone. */
export function BandPill({ band }: { band: string }) {
  const { t } = useTranslation();
  const { className, Icon } = STYLES[band] ?? COLD;
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ${className}`}>
      <Icon className="size-3.5" aria-hidden />
      {t(`backoffice.band.${band}`)}
    </span>
  );
}
