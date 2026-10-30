import { LockKeyhole } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Navigate, useNavigate } from "react-router-dom";
import { checkKey } from "../../api/backoffice";
import { ApiError, NetworkError } from "../../api/client";
import { readBackofficeKey, saveBackofficeKey } from "../../lib/backoffice";

export function BackofficeGate() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (readBackofficeKey()) {
    return <Navigate to="/backoffice" replace />;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const value = key.trim();
    if (!value) {
      setError(t("backoffice.gate.required"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await checkKey(value);
      if (saveBackofficeKey(value)) {
        navigate("/backoffice", { replace: true });
      } else {
        setError(t("backoffice.gate.storageBlocked"));
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError(t("backoffice.gate.wrong"));
      } else {
        setError(err instanceof NetworkError ? t("backoffice.networkError") : t("common.genericError"));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <main id="main" className="flex min-h-dvh items-center justify-center bg-slate-100 p-4 text-slate-700">
      <form onSubmit={submit} noValidate className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
        <LockKeyhole className="size-6 text-teal-700" aria-hidden />
        <h1 className="mt-3 text-xl font-semibold text-slate-900">{t("backoffice.title")}</h1>
        <p className="mt-1 text-sm text-slate-600">{t("backoffice.gate.intro")}</p>
        <label htmlFor="backoffice-key" className="mt-4 block text-sm font-medium text-slate-800">
          {t("backoffice.gate.label")}
        </label>
        <input
          id="backoffice-key"
          type="password"
          autoComplete="off"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          aria-invalid={error !== null}
          aria-describedby={error ? "backoffice-key-error" : undefined}
          className="mt-1 min-h-11 w-full rounded-xl border border-slate-300 px-3"
        />
        {error && (
          <p id="backoffice-key-error" role="alert" className="mt-2 text-sm text-red-800">
            {error}
          </p>
        )}
        <button type="submit" disabled={busy} className="mt-4 min-h-11 w-full rounded-xl bg-teal-700 font-medium text-white hover:bg-teal-800 disabled:opacity-60">
          {busy ? t("common.loading") : t("backoffice.gate.submit")}
        </button>
        <p className="mt-3 text-xs text-slate-500">{t("backoffice.prototypeAccess")}</p>
      </form>
    </main>
  );
}
