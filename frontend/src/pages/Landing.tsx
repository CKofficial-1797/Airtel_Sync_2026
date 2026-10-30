import { ArrowRight, CheckCircle2, Hexagon, PackageSearch } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { LanguageToggle } from "../components/LanguageToggle";

export function Landing() {
  const { t } = useTranslation();
  const benefits = [t("landing.benefit1"), t("landing.benefit2"), t("landing.benefit3")];
  return (
    <>
      <header className="flex min-h-14 items-center justify-between px-4 pt-2">
        <span className="flex items-center gap-2 font-semibold text-slate-900">
          <Hexagon className="size-6 fill-teal-700 text-teal-700" aria-hidden />
          {t("app.name")}
        </span>
        <LanguageToggle />
      </header>

      <main id="main" className="flex flex-1 flex-col px-5 pb-8 pt-6">
        <h1 className="text-[26px] font-bold leading-tight text-slate-900">{t("landing.valueProp")}</h1>

        <ul className="mt-6 space-y-3">
          {benefits.map((b) => (
            <li key={b} className="flex items-start gap-3 text-slate-700">
              <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-green-700" aria-hidden />
              <span>{b}</span>
            </li>
          ))}
        </ul>

        <div className="mt-auto space-y-3 pt-10">
          <Link
            to="/advisor"
            className="flex min-h-14 w-full items-center justify-center gap-2 rounded-2xl bg-teal-700 px-5 text-lg font-semibold text-white shadow-sm hover:bg-teal-800"
          >
            {t("landing.ctaAdvisor")}
            <ArrowRight className="size-5" aria-hidden />
          </Link>
          <Link
            to="/track"
            className="flex min-h-12 w-full items-center justify-center gap-2 rounded-2xl border border-slate-300 bg-white px-5 font-medium text-slate-700 hover:bg-slate-100"
          >
            <PackageSearch className="size-5" aria-hidden />
            {t("landing.ctaTrack")}
          </Link>
          <p className="text-center text-xs text-slate-600">{t("landing.priceNote")}</p>
        </div>
      </main>
    </>
  );
}
