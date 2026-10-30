import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { ApiError, isAbort, NetworkError } from "../api/client";
import { clearBackofficeKey } from "../lib/backoffice";

/** Back to the gate when the passcode stopped working; otherwise a message to show. */
export function useBackofficeErrors() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return useCallback(
    (e: unknown): string | null => {
      if (e instanceof ApiError && e.status === 401) {
        clearBackofficeKey();
        navigate("/backoffice/login", { replace: true });
        return null;
      }
      if (e instanceof NetworkError) {
        return t("backoffice.networkError");
      }
      return e instanceof ApiError ? e.message : t("common.genericError");
    },
    [navigate, t],
  );
}

/** Loads on mount and whenever `load` changes (memoise it on its inputs). An out-of-date answer is dropped. */
export function useBackofficeData<T>(load: (signal: AbortSignal) => Promise<T>) {
  const toMessage = useBackofficeErrors();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    load(controller.signal)
      .then((result) => {
        setData(result);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (isAbort(e)) return;
        setError(toMessage(e));
        setLoading(false);
      });
    return () => controller.abort();
  }, [load, attempt, toMessage]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { data, setData, error, loading, retry };
}
