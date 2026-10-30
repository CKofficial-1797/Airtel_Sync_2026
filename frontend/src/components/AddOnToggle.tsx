import { useTranslation } from "react-i18next";
import { formatInr } from "../lib/format";

interface Props {
  name: string;
  description?: string;
  unitPriceInr: number;
  unit: string;
  monthlyTotalInr?: number;
  on: boolean;
  disabled?: boolean;
  onToggle: (on: boolean) => void;
}

export function AddOnToggle({ name, description, unitPriceInr, unit, monthlyTotalInr, on, disabled, onToggle }: Props) {
  const { t } = useTranslation();
  const id = `addon-${name.replace(/\W+/g, "-").toLowerCase()}`;
  return (
    <li className="flex items-center gap-3 rounded-2xl border border-slate-200 bg-white p-3">
      <div className="flex-1">
        <label htmlFor={id} className="font-medium text-slate-900">
          {name}
        </label>
        {description && <p className="text-xs text-slate-600">{description}</p>}
        <p className="text-sm text-slate-600 tabular-nums">
          {on && monthlyTotalInr !== undefined
            ? t("quote.perMonthTotal", { amount: formatInr(monthlyTotalInr) })
            : t("quote.addOnPrice", { price: formatInr(unitPriceInr), unit })}
        </p>
      </div>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={on}
        disabled={disabled}
        onClick={() => onToggle(!on)}
        className={`relative h-8 w-14 shrink-0 rounded-full transition disabled:opacity-50 ${on ? "bg-teal-700" : "bg-slate-300"}`}
      >
        <span
          className={`absolute top-1 size-6 rounded-full bg-white shadow transition-all ${on ? "left-7" : "left-1"}`}
          aria-hidden
        />
      </button>
    </li>
  );
}
