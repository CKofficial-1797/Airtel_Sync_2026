import { useTranslation } from "react-i18next";
import { currentLanguage, setLanguage, type Language } from "../i18n";

const OPTIONS: Language[] = ["en", "hi"];

export function LanguageToggle() {
  const { t } = useTranslation();
  const active = currentLanguage();
  return (
    <div role="group" aria-label={t("language.label")} className="flex rounded-full bg-slate-100 p-1">
      {OPTIONS.map((lng) => (
        <button
          key={lng}
          type="button"
          onClick={() => setLanguage(lng)}
          aria-pressed={active === lng}
          lang={lng}
          className={`min-h-9 min-w-14 rounded-full px-3 text-sm font-medium transition ${
            active === lng ? "bg-teal-700 text-white shadow" : "text-slate-700 hover:bg-white"
          }`}
        >
          {t(`language.${lng}`)}
        </button>
      ))}
    </div>
  );
}
