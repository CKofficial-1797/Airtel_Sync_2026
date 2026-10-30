import { useTranslation } from "react-i18next";
import type { LiftRow } from "../../api/backoffice";

const HEIGHT_PX = 160;

/** Conversion per score decile against the average lead, with the numbers also available as a table. */
export function LiftChart({ rows }: { rows: LiftRow[] }) {
  const { t } = useTranslation();
  const ordered = [...rows].sort((a, b) => a.decile - b.decile);
  const top = Math.max(1, ...ordered.map((r) => r.lift));
  return (
    <figure>
      <figcaption className="text-sm font-medium text-slate-800">{t("backoffice.insights.liftTitle")}</figcaption>
      <div className="relative mt-3 flex items-end gap-1 border-b border-slate-300" style={{ height: HEIGHT_PX }} aria-hidden>
        <div className="pointer-events-none absolute inset-x-0 border-t border-dashed border-slate-400" style={{ bottom: (1 / top) * HEIGHT_PX }}>
          <span className="absolute right-0 -top-5 bg-white pl-1 text-xs text-slate-600">{t("backoffice.insights.liftAverage")}</span>
        </div>
        {ordered.map((r) => (
          <div key={r.decile} tabIndex={-1} className="group relative flex h-full flex-1 items-end">
            <div className="w-full rounded-t bg-teal-700 group-hover:bg-teal-800" style={{ height: `${(r.lift / top) * 100}%` }} />
            <div className="pointer-events-none absolute bottom-full left-1/2 z-10 hidden w-max -translate-x-1/2 rounded-lg bg-slate-900 px-2 py-1 text-xs text-white group-hover:block">
              {t("backoffice.insights.liftTooltip", { decile: r.decile, lift: r.lift.toFixed(2), activations: r.activations, leads: r.leads })}
            </div>
          </div>
        ))}
      </div>
      <div className="mt-1 flex gap-1 text-center text-xs text-slate-600" aria-hidden>
        {ordered.map((r) => (
          <span key={r.decile} className="flex-1">
            {r.decile}
          </span>
        ))}
      </div>
      <p className="mt-1 text-xs text-slate-600">{t("backoffice.insights.liftAxis")}</p>
      <details className="mt-2 text-sm">
        <summary className="cursor-pointer font-medium text-teal-800">{t("backoffice.insights.liftTable")}</summary>
        <div className="mt-2 overflow-x-auto">
          <table className="w-full text-left text-sm tabular-nums">
            <thead className="text-xs text-slate-600">
              <tr>
                <th scope="col" className="py-1 pr-3 font-medium">{t("backoffice.insights.decile")}</th>
                <th scope="col" className="py-1 pr-3 font-medium">{t("backoffice.insights.leads")}</th>
                <th scope="col" className="py-1 pr-3 font-medium">{t("backoffice.insights.activations")}</th>
                <th scope="col" className="py-1 pr-3 font-medium">{t("backoffice.insights.lift")}</th>
                <th scope="col" className="py-1 font-medium">{t("backoffice.insights.capture")}</th>
              </tr>
            </thead>
            <tbody>
              {ordered.map((r) => (
                <tr key={r.decile} className="border-t border-slate-100">
                  <td className="py-1 pr-3">{r.decile}</td>
                  <td className="py-1 pr-3">{r.leads}</td>
                  <td className="py-1 pr-3">{r.activations}</td>
                  <td className="py-1 pr-3">{r.lift.toFixed(2)}</td>
                  <td className="py-1">{(r.cumulativeCapture * 100).toFixed(1)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}
