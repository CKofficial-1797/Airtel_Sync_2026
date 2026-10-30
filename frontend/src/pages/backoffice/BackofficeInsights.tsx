import { useCallback, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { getInsights } from "../../api/backoffice";
import { ErrorBanner } from "../../components/ErrorBanner";
import { FunnelBars } from "../../components/backoffice/FunnelBars";
import { LiftChart } from "../../components/backoffice/LiftChart";
import { useBackofficeData } from "../../hooks/useBackofficeData";
import { MICRO_SEGMENT } from "../../lib/backoffice";
import { formatInr } from "../../lib/format";

const TH = "whitespace-nowrap py-1 pr-3 font-medium";
const TD = "py-1.5 pr-3";

export function BackofficeInsights() {
  const { t } = useTranslation();
  const load = useCallback((signal: AbortSignal) => getInsights(signal), []);
  const { data, error, loading, retry } = useBackofficeData(load);

  if (!data) {
    return error ? <ErrorBanner message={error} onRetry={retry} busy={loading} /> : <p className="text-sm text-slate-600">{t("common.loading")}</p>;
  }

  const live = data.liveFunnel;
  const percent = (fraction: number) => `${(fraction * 100).toFixed(1)}%`;
  return (
    <>
      <h1 className="text-xl font-semibold text-slate-900">{t("backoffice.insights.title")}</h1>
      <div className="mt-3 grid gap-4 lg:grid-cols-2">
        <Card title={t("backoffice.insights.funnel")}>
          <FunnelBars rows={data.funnel.map((s) => ({ label: s.stage, value: s.leads }))} />
        </Card>

        <Card title={t("backoffice.insights.model")}>
          <p className="text-sm text-slate-800">{t("backoffice.insights.capture20", { percent: percent(data.model.top20Capture) })}</p>
          <dl className="mt-2 grid grid-cols-2 gap-2 text-sm">
            <div className="rounded-lg bg-slate-50 p-2">
              <dt className="text-xs text-slate-600">{t("backoffice.insights.aucCv")}</dt>
              <dd className="text-lg font-semibold tabular-nums text-slate-900">{data.model.auc.toFixed(3)}</dd>
            </div>
            <div className="rounded-lg bg-slate-50 p-2">
              <dt className="text-xs text-slate-600">{t("backoffice.insights.aucJune", { percent: percent(data.model.juneTop20Capture) })}</dt>
              <dd className="text-lg font-semibold tabular-nums text-slate-900">{data.model.juneAuc.toFixed(3)}</dd>
            </div>
          </dl>
          <div className="mt-4">
            <LiftChart rows={data.model.liftTable} />
          </div>
        </Card>

        <Card title={t("backoffice.insights.segments")} wide>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm tabular-nums">
              <thead className="text-xs text-slate-600">
                <tr>
                  <th scope="col" className={TH}>{t("backoffice.insights.segment")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.leads")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.activations")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.conversion")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.cac")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.bundle")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.payback")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.churn")}</th>
                </tr>
              </thead>
              <tbody>
                {data.segmentEconomics.map((s) => {
                  const micro = s.segment === MICRO_SEGMENT;
                  return (
                    <tr key={s.segment} className={`border-t border-slate-100 ${micro ? "bg-amber-50" : ""}`}>
                      <th scope="row" className={`${TD} font-medium text-slate-800`}>{s.segment}</th>
                      <td className={TD}>{s.leads.toLocaleString("en-IN")}</td>
                      <td className={TD}>{s.activations}</td>
                      <td className={TD}>{s.conversion.toFixed(1)}%</td>
                      <td className={TD}>{formatInr(s.cacPerActivation)}</td>
                      <td className={TD}>{formatInr(s.avgMonthlyBundle)}</td>
                      <td className={`${TD} ${micro ? "font-semibold text-amber-900" : ""}`}>
                        {t("backoffice.insights.months", { value: s.paybackMonths.toFixed(1) })}
                        {micro && <span className="ml-1 text-xs font-normal">{t("backoffice.insights.microPayback")}</span>}
                      </td>
                      <td className={TD}>{s.churn6m.toFixed(1)}%</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>

        <Card title={t("backoffice.insights.misSelling")}>
          <p className="text-2xl font-semibold tabular-nums text-slate-900">
            {t("backoffice.insights.misSellingValue", { bad: data.misSelling.bundlesWithViolations, total: data.misSelling.activatedChecked })}
          </p>
          <p className="mt-1 text-sm text-slate-700">{t("backoffice.insights.misSellingBody", { percent: data.misSelling.misSoldPercent.toFixed(1) })}</p>
          <p className="mt-1 text-xs text-slate-600">{t("backoffice.insights.misSellingStricter", { count: data.misSelling.registryActiveViolations })}</p>
        </Card>

        <Card title={t("backoffice.insights.live")}>
          <FunnelBars
            rows={[
              { label: t("backoffice.insights.liveSessions"), value: live.sessions },
              { label: t("backoffice.insights.liveRecommended"), value: live.recommended },
              { label: t("backoffice.insights.liveCheckout"), value: live.checkout },
              { label: t("backoffice.insights.liveKyc"), value: live.kycVerified },
              { label: t("backoffice.insights.liveActive"), value: live.active },
            ]}
          />
        </Card>

        <Card title={t("backoffice.insights.sources")} wide>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm tabular-nums">
              <thead className="text-xs text-slate-600">
                <tr>
                  <th scope="col" className={TH}>{t("backoffice.insights.source")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.leads")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.activations")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.conversion")}</th>
                  <th scope="col" className={TH}>{t("backoffice.insights.cac")}</th>
                </tr>
              </thead>
              <tbody>
                {data.sourceEconomics.map((s) => (
                  <tr key={s.source} className="border-t border-slate-100">
                    <th scope="row" className={`${TD} font-medium text-slate-800`}>{s.source}</th>
                    <td className={TD}>{s.leads.toLocaleString("en-IN")}</td>
                    <td className={TD}>{s.activations}</td>
                    <td className={TD}>{s.conversion.toFixed(1)}%</td>
                    <td className={TD}>{formatInr(s.cacPerActivation)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </>
  );
}

function Card({ title, wide, children }: { title: string; wide?: boolean; children: ReactNode }) {
  return (
    <section className={`rounded-2xl border border-slate-200 bg-white p-4 ${wide ? "lg:col-span-2" : ""}`}>
      <h2 className="mb-3 text-sm font-semibold text-slate-900">{title}</h2>
      {children}
    </section>
  );
}
