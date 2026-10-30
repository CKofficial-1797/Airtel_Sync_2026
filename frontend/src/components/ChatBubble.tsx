import { AlertCircle } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";

/** Renders **bold** and simple "* " / "- " bullet lines from the advisor's plain-text replies. */
function renderText(text: string): ReactNode[] {
  return text.split("\n").map((line, i) => {
    const bullet = /^\s*[*-]\s+/.test(line);
    const content = line.replace(/^\s*[*-]\s+/, "");
    const parts = content.split(/(\*\*[^*]+\*\*)/g).map((part, j) =>
      part.startsWith("**") && part.endsWith("**") ? (
        <strong key={j} className="font-semibold text-slate-900">
          {part.slice(2, -2)}
        </strong>
      ) : (
        <span key={j}>{part}</span>
      ),
    );
    return (
      <span key={i} className={`block ${bullet ? "pl-4 -indent-3" : ""} ${line.trim() === "" ? "h-2" : ""}`}>
        {bullet && <span aria-hidden>• </span>}
        {parts}
      </span>
    );
  });
}

interface Props {
  role: "user" | "assistant";
  content: string;
  failed?: boolean;
}

export function ChatBubble({ role, content, failed }: Props) {
  const { t } = useTranslation();
  const mine = role === "user";
  return (
    <div className={`flex ${mine ? "justify-end" : "justify-start"}`}>
      <div className="max-w-[85%]">
        <div
          className={`rounded-2xl px-4 py-2.5 text-[15px] leading-relaxed break-words ${
            mine
              ? "rounded-br-md bg-teal-700 text-white"
              : "rounded-bl-md border border-slate-200 bg-white text-slate-700"
          } ${failed ? "opacity-80 ring-2 ring-amber-500" : ""}`}
        >
          {mine ? <span className="whitespace-pre-wrap">{content}</span> : renderText(content)}
        </div>
        {failed && (
          <p className="mt-1 flex items-center justify-end gap-1 text-xs text-amber-700">
            <AlertCircle className="size-3.5" aria-hidden />
            {t("advisor.notDelivered")}
          </p>
        )}
      </div>
    </div>
  );
}
