import { X } from "lucide-react";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";

const AUTO_DISMISS_MS = 6000;

/** Short confirmation at the bottom of the phone frame. Announced to screen readers; dismisses itself. */
export function Toast({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  const { t } = useTranslation();

  useEffect(() => {
    const timer = window.setTimeout(onDismiss, AUTO_DISMISS_MS);
    return () => window.clearTimeout(timer);
  }, [message, onDismiss]);

  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-4 z-40 flex justify-center px-4">
      <div role="status" className="pointer-events-auto flex w-full max-w-sm items-center gap-2 rounded-xl bg-slate-900 py-2 pl-4 pr-2 text-sm text-white shadow-lg">
        <p className="flex-1">{message}</p>
        <button
          type="button"
          onClick={onDismiss}
          className="flex size-9 shrink-0 items-center justify-center rounded-lg hover:bg-slate-700"
          aria-label={t("common.dismiss")}
        >
          <X className="size-4" aria-hidden />
        </button>
      </div>
    </div>
  );
}
