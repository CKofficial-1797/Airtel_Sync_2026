import { useTranslation } from "react-i18next";

export interface Chip {
  label: string;
  onSelect: () => void;
}

export function QuickReplies({ chips, disabled }: { chips: Chip[]; disabled?: boolean }) {
  const { t } = useTranslation();
  if (chips.length === 0) return null;
  return (
    <div role="group" aria-label={t("advisor.quick.label")} className="flex gap-2 overflow-x-auto px-3 pb-2 pt-1">
      {chips.map((chip) => (
        <button
          key={chip.label}
          type="button"
          disabled={disabled}
          onClick={chip.onSelect}
          className="min-h-11 shrink-0 rounded-full border border-teal-200 bg-teal-50 px-4 text-sm font-medium text-teal-800 transition hover:bg-teal-100 disabled:opacity-50"
        >
          {chip.label}
        </button>
      ))}
    </div>
  );
}
