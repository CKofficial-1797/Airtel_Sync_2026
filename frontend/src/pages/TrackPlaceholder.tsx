import { PackageSearch } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Header } from "../components/Layout";

export function TrackPlaceholder() {
  const { t } = useTranslation();
  return (
    <>
      <Header title={t("track.title")} backTo="/" />
      <main id="main" className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
        <PackageSearch className="size-12 text-teal-700" aria-hidden />
        <p className="text-slate-700">{t("track.body")}</p>
      </main>
    </>
  );
}
