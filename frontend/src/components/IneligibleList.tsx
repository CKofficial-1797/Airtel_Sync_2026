import { ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { EligibilityResult } from "../api/types";

export function IneligibleList({ items }: { items: EligibilityResult[] }) {
  const { t } = useTranslation();
  if (items.length === 0) return null;
  return (
    <details className="group rounded-2xl border border-slate-200 bg-white">
      <summary className="flex min-h-12 cursor-pointer list-none items-center gap-2 px-4 font-medium text-slate-700">
        <ChevronRight className="size-4 transition group-open:rotate-90" aria-hidden />
        {t("quote.notAvailable", { count: items.length })}
      </summary>
      <ul className="space-y-2 px-4 pb-4">
        {items.map((item) => (
          <li key={item.productId} className="text-sm">
            <span className="font-medium text-slate-600">{item.productName}</span>
            <span className="block text-slate-600">{item.reasons.map((r) => r.message).join(" · ")}</span>
          </li>
        ))}
      </ul>
    </details>
  );
}
