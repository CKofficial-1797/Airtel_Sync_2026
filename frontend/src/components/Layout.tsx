import { ArrowLeft } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";

/** Mobile-first column; on desktop a centred phone-width frame. */
export function Layout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  return (
    <div className="min-h-dvh bg-slate-200 sm:py-6">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded-lg focus:bg-white focus:px-3 focus:py-2"
      >
        {t("app.skipToContent")}
      </a>
      <div className="mx-auto flex min-h-dvh max-w-md flex-col bg-slate-50 text-slate-700 sm:min-h-[calc(100dvh-3rem)] sm:overflow-hidden sm:rounded-3xl sm:shadow-xl">
        {children}
      </div>
    </div>
  );
}

interface HeaderProps {
  title: string;
  backTo?: string;
  actions?: ReactNode;
}

export function Header({ title, backTo, actions }: HeaderProps) {
  const { t } = useTranslation();
  return (
    <header className="sticky top-0 z-20 flex min-h-14 items-center gap-2 border-b border-slate-200 bg-white/95 px-2 backdrop-blur">
      {backTo ? (
        <Link
          to={backTo}
          className="flex size-11 items-center justify-center rounded-full text-slate-700 hover:bg-slate-100"
          aria-label={t("common.back")}
        >
          <ArrowLeft className="size-5" aria-hidden />
        </Link>
      ) : (
        <span className="w-2" />
      )}
      <h1 className="flex-1 truncate text-lg font-semibold text-slate-900">{title}</h1>
      {actions}
    </header>
  );
}
