import { useTranslation } from "react-i18next";

/** One horizontal bar per stage, each labelled with its count and its share of the first stage. */
export function FunnelBars({ rows }: { rows: { label: string; value: number }[] }) {
  const { t } = useTranslation();
  const top = rows[0]?.value ?? 0;
  return (
    <ol className="space-y-2">
      {rows.map((row) => {
        const share = top === 0 ? 0 : (row.value / top) * 100;
        return (
          <li key={row.label}>
            <div className="flex justify-between gap-2 text-sm">
              <span className="text-slate-800">{row.label}</span>
              <span className="tabular-nums text-slate-600">{t("backoffice.insights.funnelValue", { value: row.value.toLocaleString("en-IN"), share: share.toFixed(1) })}</span>
            </div>
            <div className="mt-1 h-2 rounded bg-slate-100" aria-hidden>
              <div className="h-2 rounded bg-teal-700" style={{ width: `${share}%` }} />
            </div>
          </li>
        );
      })}
    </ol>
  );
}
