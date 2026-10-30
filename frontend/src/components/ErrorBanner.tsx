import { AlertTriangle, RotateCw, X } from "lucide-react";
import { useTranslation } from "react-i18next";

interface Props {
  message: string;
  onRetry?: () => void;
  onDismiss?: () => void;
  busy?: boolean;
}

export function ErrorBanner({ message, onRetry, onDismiss, busy }: Props) {
  const { t } = useTranslation();
  return (
    <div role="alert" className="mx-3 flex items-start gap-2 rounded-xl border border-red-200 bg-red-100 p-3 text-sm text-red-800">
      <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden />
      <p className="flex-1">{message}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          disabled={busy}
          className="flex min-h-9 items-center gap-1 rounded-lg bg-red-700 px-3 font-medium text-white hover:bg-red-800 disabled:opacity-60"
        >
          <RotateCw className="size-4" aria-hidden />
          {t("common.retry")}
        </button>
      )}
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="flex size-9 items-center justify-center rounded-lg hover:bg-red-200"
          aria-label={t("common.dismiss")}
        >
          <X className="size-4" aria-hidden />
        </button>
      )}
    </div>
  );
}
