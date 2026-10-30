import { ChevronLeft, ChevronRight } from "lucide-react";
import { useCallback, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useSearchParams } from "react-router-dom";
import { getQueue, getReengagement, type QueuePage, type QueueRow, type Reengagement } from "../../api/backoffice";
import { ErrorBanner } from "../../components/ErrorBanner";
import { BandPill } from "../../components/backoffice/BandPill";
import { ChannelBadge } from "../../components/backoffice/ChannelBadge";
import { useBackofficeData } from "../../hooks/useBackofficeData";
import { BANDS, CHANNELS, formatStage, SEGMENTS, STATUSES } from "../../lib/backoffice";
import { formatDate, formatInr } from "../../lib/format";

const PAGE_SIZE = 25;
const TABS = ["all", "SESSION", "ORDER", "reengage"] as const;
type Tab = (typeof TABS)[number];
const FILTERS = ["segment", "tier", "band", "channel", "status"] as const;
type Filter = (typeof FILTERS)[number];

type Loaded = { page: QueuePage; reengagement: Reengagement | null };

export function BackofficeQueue() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.find((x) => x === params.get("tab")) ?? "all";
  const page = Math.max(0, Number(params.get("page")) || 0);
  const [filters, setFilters] = useState<Record<Filter, string>>(() => ({
    segment: params.get("segment") ?? "",
    tier: params.get("tier") ?? "",
    band: params.get("band") ?? "",
    channel: params.get("channel") ?? "",
    status: params.get("status") ?? "",
  }));

  const load = useCallback(
    async (signal: AbortSignal): Promise<Loaded> => {
      if (tab === "reengage") {
        const reengagement = await getReengagement(page, PAGE_SIZE, signal);
        return { page: reengagement.leads, reengagement };
      }
      const kind = tab === "all" ? undefined : tab;
      return { page: await getQueue({ kind, ...filters, page, size: PAGE_SIZE }, signal), reengagement: null };
    },
    [tab, page, filters],
  );
  const { data, error, loading, retry } = useBackofficeData(load);

  function update(changes: Record<string, string>) {
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.delete("page");
      for (const [name, value] of Object.entries(changes)) {
        if (value) next.set(name, value);
        else next.delete(name);
      }
      return next;
    });
  }

  function setFilter(name: Filter, value: string) {
    setFilters((current) => ({ ...current, [name]: value }));
    update({ [name]: value });
  }

  function goToPage(next: number) {
    setParams((current) => {
      const updated = new URLSearchParams(current);
      updated.set("page", String(next));
      return updated;
    });
  }

  const options: Record<Filter, { value: string; label: string }[]> = {
    segment: SEGMENTS.map((s) => ({ value: s, label: s })),
    tier: ["1", "2"].map((x) => ({ value: x, label: t("backoffice.queue.tierValue", { tier: x }) })),
    band: BANDS.map((x) => ({ value: x, label: t(`backoffice.band.${x}`) })),
    channel: CHANNELS.map((x) => ({ value: x, label: t(`backoffice.channel.${x}`) })),
    status: STATUSES.map((x) => ({ value: x, label: t(`backoffice.status.${x}`) })),
  };

  const rows = data?.page.rows ?? [];
  const total = data?.page.total ?? 0;
  const first = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const last = Math.min((page + 1) * PAGE_SIZE, total);

  return (
    <>
      <h1 className="text-xl font-semibold text-slate-900">{t("backoffice.queue.title")}</h1>
      <div role="tablist" aria-label={t("backoffice.queue.title")} className="mt-3 flex flex-wrap gap-1 border-b border-slate-200">
        {TABS.map((x) => (
          <button
            key={x}
            type="button"
            role="tab"
            aria-selected={tab === x}
            onClick={() => update({ tab: x === "all" ? "" : x })}
            className={`min-h-10 border-b-2 px-3 text-sm font-medium ${tab === x ? "border-teal-700 text-teal-800" : "border-transparent text-slate-600 hover:text-slate-900"}`}
          >
            {t(`backoffice.queue.tab.${x}`)}
          </button>
        ))}
      </div>

      {tab === "reengage" ? (
        data?.reengagement && <ReasonCounts data={data.reengagement} />
      ) : (
        <div className="mt-3 flex flex-wrap gap-3">
          {FILTERS.map((name) => (
            <label key={name} className="text-xs font-medium text-slate-600">
              {t(`backoffice.queue.filter.${name}`)}
              <select
                value={filters[name]}
                onChange={(e) => setFilter(name, e.target.value)}
                className="mt-1 block min-h-10 rounded-lg border border-slate-300 bg-white px-2 text-sm font-normal text-slate-800"
              >
                <option value="">{t("backoffice.queue.any")}</option>
                {options[name].map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </label>
          ))}
        </div>
      )}

      {error && (
        <div className="mt-3 -mx-3">
          <ErrorBanner message={error} onRetry={retry} busy={loading} />
        </div>
      )}

      <div className="mt-3 rounded-2xl border border-slate-200 bg-white" aria-busy={loading}>
        {rows.length === 0 ? (
          <p className="p-6 text-center text-sm text-slate-600">{loading ? t("common.loading") : t("backoffice.queue.empty")}</p>
        ) : (
          <table className="block w-full text-left text-sm md:table">
            <thead className="hidden text-xs text-slate-600 md:table-header-group">
              <tr>
                <th scope="col" className="p-3 font-medium">{t("backoffice.queue.col.score")}</th>
                <th scope="col" className="p-3 font-medium">{t("backoffice.queue.col.business")}</th>
                <th scope="col" className="p-3 font-medium">{t("backoffice.queue.col.size")}</th>
                <th scope="col" className="p-3 font-medium">{t("backoffice.queue.col.stopped")}</th>
                <th scope="col" className="p-3 font-medium">{t("backoffice.queue.col.action")}</th>
                <th scope="col" className="p-3 font-medium"><span className="sr-only">{t("backoffice.queue.col.assign")}</span></th>
              </tr>
            </thead>
            <tbody className="block md:table-row-group">
              {rows.map((row) => (
                <LeadRow key={`${row.kind}/${row.ref}`} row={row} />
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="mt-3 flex items-center justify-between gap-2 text-sm text-slate-600">
        <p aria-live="polite">{t("backoffice.queue.range", { first, last, total: total.toLocaleString("en-IN") })}</p>
        <div className="flex gap-1">
          <button
            type="button"
            onClick={() => goToPage(page - 1)}
            disabled={page === 0 || loading}
            className="flex min-h-10 items-center gap-1 rounded-lg border border-slate-300 bg-white px-3 font-medium disabled:opacity-50"
          >
            <ChevronLeft className="size-4" aria-hidden />
            {t("backoffice.queue.previous")}
          </button>
          <button
            type="button"
            onClick={() => goToPage(page + 1)}
            disabled={last >= total || loading}
            className="flex min-h-10 items-center gap-1 rounded-lg border border-slate-300 bg-white px-3 font-medium disabled:opacity-50"
          >
            {t("backoffice.queue.next")}
            <ChevronRight className="size-4" aria-hidden />
          </button>
        </div>
      </div>
    </>
  );
}

function ReasonCounts({ data }: { data: Reengagement }) {
  const { t } = useTranslation();
  return (
    <section className="mt-3">
      <p className="text-sm text-slate-700">
        {t("backoffice.queue.reengageIntro", { process: data.processTotal.toLocaleString("en-IN"), price: data.priceTotal.toLocaleString("en-IN") })}
      </p>
      <ul className="mt-2 flex flex-wrap gap-2">
        {data.byReason.map((r) => (
          <li key={r.reason} className="rounded-lg border border-slate-200 bg-white px-3 py-1 text-sm">
            <span className="text-slate-700">{r.reason}</span> <span className="font-semibold tabular-nums text-slate-900">{r.leads}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function LeadRow({ row }: { row: QueueRow }) {
  const { t, i18n } = useTranslation();
  const reason = row.topReasons[0];
  const size = [row.employees != null && t("backoffice.queue.people", { count: row.employees }), row.sites != null && t("backoffice.queue.sites", { count: row.sites })]
    .filter(Boolean)
    .join(" · ");
  const place = [row.city, row.tier != null && t("backoffice.queue.tierValue", { tier: row.tier })].filter(Boolean).join(", ");
  const cell = "block px-3 py-1 align-top md:table-cell md:py-3";
  return (
    <tr className="block border-t border-slate-100 py-2 first:border-t-0 md:table-row md:py-0">
      <td className={cell}>
        <BandPill band={row.band} />
        <p className="mt-1 font-semibold tabular-nums text-slate-900">{t("backoffice.queue.scoreValue", { score: row.score })}</p>
        {reason && (
          <p className="max-w-44 text-xs text-slate-600">
            {reason.direction === "+" ? "+ " : "− "}
            {i18n.language === "hi" ? reason.labelHi : reason.labelEn}
          </p>
        )}
      </td>
      <td className={cell}>
        <Link to={`/backoffice/leads/${row.kind}/${encodeURIComponent(row.ref)}`} className="font-medium text-teal-800 underline-offset-2 hover:underline">
          {row.businessName ?? row.businessType ?? t("backoffice.queue.unnamed")}
        </Link>
        <p className="text-xs text-slate-600">
          {t(`backoffice.kind.${row.kind}`)} · {row.displayRef}
        </p>
      </td>
      <td className={cell}>
        <p className="text-slate-800">{row.segment ?? t("backoffice.queue.unknownSegment")}</p>
        <p className="text-xs text-slate-600">{[place, size].filter(Boolean).join(" · ")}</p>
      </td>
      <td className={cell}>
        <p className="text-slate-800">{formatStage(row.stoppedAt)}</p>
        <p className="text-xs text-slate-600">
          {formatDate(row.lastActivity, i18n.language)}
          {row.quoteMonthlyInr != null && ` · ${t("backoffice.queue.quote", { amount: formatInr(row.quoteMonthlyInr) })}`}
        </p>
      </td>
      <td className={cell}>
        <p className="font-medium text-slate-800">{t(`backoffice.action.${row.suggestedAction}`)}</p>
        <p className="mt-1">
          <ChannelBadge channel={row.recommendedChannel} />
        </p>
        <p className="mt-1 line-clamp-2 max-w-64 text-xs text-slate-600" title={row.channelWhy}>
          {row.channelWhy}
        </p>
      </td>
      <td className={cell}>
        {row.assignment && (
          <p className="mb-1 text-xs text-slate-600">
            {t(`backoffice.status.${row.status}`)}
            {row.assignment.assigneeLabel && ` · ${row.assignment.assigneeLabel}`}
          </p>
        )}
        <Link
          to={`/backoffice/leads/${row.kind}/${encodeURIComponent(row.ref)}#assign`}
          className="inline-flex min-h-9 items-center rounded-lg border border-teal-700 px-3 text-sm font-medium text-teal-800 hover:bg-teal-50"
          aria-label={t("backoffice.queue.assignLead", { name: row.businessName ?? row.displayRef })}
        >
          {t(row.assignment ? "backoffice.queue.manage" : "backoffice.queue.assign")}
        </Link>
      </td>
    </tr>
  );
}
