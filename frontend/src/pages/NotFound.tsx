import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";

export function NotFound() {
  const { t } = useTranslation();
  return (
    <main id="main" className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
      <h1 className="text-2xl font-semibold text-slate-900">{t("notFound.title")}</h1>
      <p className="text-slate-600">{t("notFound.body")}</p>
      <Link to="/" className="mt-2 flex min-h-12 items-center rounded-xl bg-teal-700 px-5 font-semibold text-white">
        {t("notFound.home")}
      </Link>
    </main>
  );
}
