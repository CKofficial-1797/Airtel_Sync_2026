import { Minus, Plus, ShieldAlert, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { QuoteLine } from "../api/types";
import { MAX_QTY, MIN_QTY } from "../hooks/useQuote";
import { formatInr } from "../lib/format";

interface Props {
  line: QuoteLine;
  quantity: number;
  description?: string;
  canRemove: boolean;
  onQuantity: (q: number) => void;
  onRemove: () => void;
}

export function QuoteLineItem({ line, quantity, description, canRemove, onQuantity, onRemove }: Props) {
  const { t } = useTranslation();
  const name = line.productName;
  return (
    <li className="rounded-2xl border border-slate-200 bg-white p-4">
      <div className="flex items-start gap-2">
        <div className="flex-1">
          <h3 className="font-semibold text-slate-900">{name}</h3>
          {description && <p className="mt-0.5 text-sm text-slate-600">{description}</p>}
          {line.conditional && (
            <span className="mt-1.5 inline-flex items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-800">
              <ShieldAlert className="size-3" aria-hidden />
              {t("card.conditional")}
            </span>
          )}
        </div>
        {canRemove && (
          <button
            type="button"
            onClick={onRemove}
            className="flex size-11 items-center justify-center rounded-full text-slate-500 hover:bg-red-50 hover:text-red-700"
            aria-label={t("quote.remove", { name })}
          >
            <Trash2 className="size-5" aria-hidden />
          </button>
        )}
      </div>

      <div className="mt-3 flex items-center justify-between gap-3">
        <div className="flex items-center rounded-xl border border-slate-200">
          <button
            type="button"
            onClick={() => onQuantity(quantity - 1)}
            disabled={quantity <= MIN_QTY}
            className="flex size-11 items-center justify-center rounded-l-xl text-slate-700 hover:bg-slate-100 disabled:opacity-40"
            aria-label={t("quote.decrease", { name })}
          >
            <Minus className="size-4" aria-hidden />
          </button>
          <input
            type="number"
            inputMode="numeric"
            min={MIN_QTY}
            max={MAX_QTY}
            value={quantity}
            onChange={(e) => {
              const v = Number(e.target.value);
              if (Number.isFinite(v) && v >= MIN_QTY) onQuantity(v);
            }}
            className="h-11 w-14 border-x border-slate-200 text-center font-semibold text-slate-900 tabular-nums [appearance:textfield] focus:outline-none focus:ring-2 focus:ring-teal-700 [&::-webkit-inner-spin-button]:appearance-none"
            aria-label={t("quote.quantity", { name })}
          />
          <button
            type="button"
            onClick={() => onQuantity(quantity + 1)}
            disabled={quantity >= MAX_QTY}
            className="flex size-11 items-center justify-center rounded-r-xl text-slate-700 hover:bg-slate-100 disabled:opacity-40"
            aria-label={t("quote.increase", { name })}
          >
            <Plus className="size-4" aria-hidden />
          </button>
        </div>
        <div className="text-right">
          <p className="text-xs text-slate-600">
            {t("quote.perUnit", { price: formatInr(line.unitPriceInr), unit: line.unit })}
          </p>
          <p className="font-semibold text-slate-900 tabular-nums">
            {t("quote.perMonthTotal", { amount: formatInr(line.monthlyTotalInr) })}
          </p>
          {line.setupFeeInr > 0 && (
            <p className="text-xs text-slate-600">{t("quote.setupFee", { amount: formatInr(line.setupFeeInr) })}</p>
          )}
        </div>
      </div>
    </li>
  );
}
