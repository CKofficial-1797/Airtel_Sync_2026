import { LogOut } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Navigate, NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearBackofficeKey, readBackofficeKey } from "../../lib/backoffice";

function navClass({ isActive }: { isActive: boolean }): string {
  return `flex min-h-10 items-center rounded-lg px-3 text-sm font-medium ${isActive ? "bg-teal-700 text-white" : "text-slate-700 hover:bg-slate-100"}`;
}

/** Everything behind the passcode: no key, straight to the gate. Laptop-first, unlike the customer's phone frame. */
export function BackofficeShell() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  if (!readBackofficeKey()) {
    return <Navigate to="/backoffice/login" replace />;
  }
  return (
    <div className="min-h-dvh bg-slate-50 text-slate-700">
      <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/95 backdrop-blur">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-2 px-4 py-2">
          <p className="font-semibold text-slate-900">{t("backoffice.title")}</p>
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">{t("backoffice.prototypeAccess")}</span>
          <nav aria-label={t("backoffice.title")} className="ml-auto flex items-center gap-1">
            <NavLink to="/backoffice" end className={navClass}>
              {t("backoffice.nav.queue")}
            </NavLink>
            <NavLink to="/backoffice/insights" className={navClass}>
              {t("backoffice.nav.insights")}
            </NavLink>
            <button
              type="button"
              onClick={() => {
                clearBackofficeKey();
                navigate("/backoffice/login");
              }}
              className="flex min-h-10 items-center gap-1 rounded-lg px-3 text-sm font-medium text-slate-700 hover:bg-slate-100"
            >
              <LogOut className="size-4" aria-hidden />
              {t("backoffice.nav.signOut")}
            </button>
          </nav>
        </div>
      </header>
      <main id="main" className="mx-auto max-w-7xl px-4 py-4">
        <Outlet />
      </main>
    </div>
  );
}
