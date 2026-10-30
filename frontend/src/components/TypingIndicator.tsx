import { useTranslation } from "react-i18next";

export function TypingIndicator() {
  const { t } = useTranslation();
  return (
    <div className="flex justify-start" role="status" aria-label={t("advisor.typing")}>
      <div className="flex gap-1.5 rounded-2xl rounded-bl-md border border-slate-200 bg-white px-4 py-3.5">
        {[0, 150, 300].map((delay) => (
          <span
            key={delay}
            className="size-2 animate-bounce rounded-full bg-slate-400"
            style={{ animationDelay: `${delay}ms` }}
            aria-hidden
          />
        ))}
      </div>
    </div>
  );
}
