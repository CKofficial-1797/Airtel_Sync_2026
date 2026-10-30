import { ArrowLeft, PhoneOff } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useParams } from "react-router-dom";
import { assignLead, getLead, setLeadStatus, type LeadDetail } from "../../api/backoffice";
import { ErrorBanner } from "../../components/ErrorBanner";
import { Toast } from "../../components/Toast";
import { BandPill } from "../../components/backoffice/BandPill";
import { ChannelBadge } from "../../components/backoffice/ChannelBadge";
import { ReasonBars } from "../../components/backoffice/ReasonBars";
import { useBackofficeData, useBackofficeErrors } from "../../hooks/useBackofficeData";
import { CHANNELS, formatStage, MICRO_SEGMENT, STATUSES } from "../../lib/backoffice";
import { formatDate, formatInr } from "../../lib/format";

const CARD = "rounded-2xl border border-slate-200 bg-white p-4";
const FIELD = "mt-1 min-h-10 w-full rounded-lg border border-slate-300 bg-white px-2 text-sm font-normal text-slate-800";
const PROFILE_FIELDS = ["leadSource", "businessType", "city", "employees", "sites", "gstin", "registryStatus", "currentInternet", "monthlyTelecomSpendInr"] as const;

export function BackofficeLead() {
  const { t, i18n } = useTranslation();
  const { kind = "", ref = "" } = useParams();
  const load = useCallback((signal: AbortSignal) => getLead(kind, ref, signal), [kind, ref]);
  const { data, setData, error, loading, retry } = useBackofficeData(load);
  const [toast, setToast] = useState<string | null>(null);
  const dismissToast = useCallback(() => setToast(null), []);

  if (!data) {
    return (
      <>
        <BackLink />
        {error ? <ErrorBanner message={error} onRetry={retry} busy={loading} /> : <p className="mt-4 text-sm text-slate-600">{t("common.loading")}</p>}
      </>
    );
  }

  const { lead } = data;
  return (
    <>
      <BackLink />
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <h1 className="text-xl font-semibold text-slate-900">{lead.businessName ?? lead.businessType ?? t("backoffice.queue.unnamed")}</h1>
        <BandPill band={lead.band} />
        <span className="text-sm tabular-nums text-slate-700">
          {t("backoffice.lead.scoreLine", { score: lead.score, probability: (lead.probability * 100).toFixed(1) })}
        </span>
      </div>
      <p className="text-sm text-slate-600">
        {t(`backoffice.kind.${lead.kind}`)} · {lead.displayRef} · {formatStage(lead.stoppedAt)}
      </p>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card title={t("backoffice.lead.suggested")}>
            <div className="flex flex-wrap items-center gap-2">
              <p className="font-medium text-slate-900">{t(`backoffice.action.${lead.suggestedAction}`)}</p>
              <ChannelBadge channel={lead.recommendedChannel} />
            </div>
            <p className="mt-1 text-sm text-slate-700">{lead.channelWhy}</p>
          </Card>

          <Card title={t("backoffice.lead.why")}>
            <ReasonBars reasons={lead.topReasons} />
          </Card>

          {data.failedChecks.length > 0 && (
            <Card title={t("backoffice.lead.failedChecks")}>
              <ul className="space-y-3 text-sm">
                {data.failedChecks.map((c) => (
                  <li key={c.code}>
                    <p className="font-medium text-slate-900">
                      {c.code}: {i18n.language === "hi" ? c.messageHi : c.messageEn}
                    </p>
                    {c.fixHintEn && <p className="text-slate-700">{i18n.language === "hi" ? c.fixHintHi : c.fixHintEn}</p>}
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {data.recentMessages.length > 0 && (
            <Card title={t("backoffice.lead.chat")}>
              <ul className="space-y-2 text-sm">
                {data.recentMessages.map((m, i) => (
                  <li key={i} className={`rounded-xl px-3 py-2 ${m.role === "user" ? "bg-slate-100" : "bg-teal-50"}`}>
                    <p className="text-xs font-medium text-slate-600">{t(m.role === "user" ? "backoffice.lead.owner" : "backoffice.lead.advisor")}</p>
                    <p className="whitespace-pre-wrap text-slate-800">{m.content}</p>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          {data.quote && (
            <Card title={t("backoffice.lead.quote")}>
              <p className="text-sm text-slate-800">
                {t("backoffice.lead.quoteLine", {
                  monthly: formatInr(data.quote.monthlyInr ?? 0),
                  oneTime: formatInr(data.quote.oneTimeInr ?? 0),
                  months: data.quote.contractMonths,
                })}
              </p>
              <ul className="mt-2 list-inside list-disc text-sm text-slate-700">
                {data.quote.lines.map((l) => (
                  <li key={l.productName}>
                    {l.productName} × {l.quantity}
                  </li>
                ))}
              </ul>
            </Card>
          )}
        </div>

        <div className="space-y-4">
          <AssignCard data={data} onChange={setData} onDone={setToast} />

          <Card title={t("backoffice.lead.profile")}>
            <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
              <dt className="text-slate-600">{t("backoffice.lead.segment")}</dt>
              <dd className="text-slate-800">{lead.segment ?? t("backoffice.queue.unknownSegment")}</dd>
              <dt className="text-slate-600">{t("backoffice.lead.gstinStatus")}</dt>
              <dd className="text-slate-800">{lead.gstinStatus}</dd>
              {PROFILE_FIELDS.filter((f) => data.profile?.[f] != null).map((f) => (
                <div key={f} className="contents">
                  <dt className="text-slate-600">{t(`backoffice.lead.field.${f}`)}</dt>
                  <dd className="break-words text-slate-800">{String(data.profile?.[f])}</dd>
                </div>
              ))}
            </dl>
            <p className="mt-3 flex items-start gap-2 rounded-lg bg-amber-50 p-2 text-xs text-amber-900">
              <PhoneOff className="mt-0.5 size-3.5 shrink-0" aria-hidden />
              {data.contactNote}
            </p>
          </Card>

          <Card title={t("backoffice.lead.history")}>
            {data.history.length === 0 ? (
              <p className="text-sm text-slate-600">{t("backoffice.lead.noHistory")}</p>
            ) : (
              <ol className="space-y-2 text-sm">
                {data.history.map((h, i) => (
                  <li key={i} className="border-l-2 border-slate-200 pl-2">
                    <p className="font-medium text-slate-800">
                      {t(`backoffice.status.${h.status}`)} · {t(`backoffice.channel.${h.channel}`)}
                      {h.assigneeLabel && ` · ${h.assigneeLabel}`}
                    </p>
                    {h.note && <p className="text-slate-700">{h.note}</p>}
                    <p className="text-xs text-slate-500">{formatDate(h.createdAt, i18n.language)}</p>
                  </li>
                ))}
              </ol>
            )}
          </Card>
        </div>
      </div>
      {toast && <Toast message={toast} onDismiss={dismissToast} />}
    </>
  );
}

function BackLink() {
  const { t } = useTranslation();
  return (
    <Link to="/backoffice" className="inline-flex min-h-10 items-center gap-1 text-sm font-medium text-teal-800">
      <ArrowLeft className="size-4" aria-hidden />
      {t("backoffice.lead.back")}
    </Link>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className={CARD}>
      <h2 className="mb-2 text-sm font-semibold text-slate-900">{title}</h2>
      {children}
    </section>
  );
}

interface AssignProps {
  data: LeadDetail;
  onChange: (next: LeadDetail) => void;
  onDone: (message: string) => void;
}

function AssignCard({ data, onChange, onDone }: AssignProps) {
  const { t } = useTranslation();
  const toMessage = useBackofficeErrors();
  const { hash } = useLocation();
  const { lead } = data;
  const micro = lead.segment === MICRO_SEGMENT;
  const [channel, setChannel] = useState(lead.assignment?.channel ?? lead.recommendedChannel);
  const [assignee, setAssignee] = useState(lead.assignment?.assigneeLabel ?? "");
  const [status, setStatus] = useState(lead.status);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const firstField = useRef<HTMLSelectElement>(null);

  useEffect(() => {
    if (hash === "#assign") firstField.current?.focus();
  }, [hash]);

  async function run(action: () => Promise<LeadDetail>, done: string) {
    setBusy(true);
    setError(null);
    try {
      onChange(await action());
      setNote("");
      onDone(done);
    } catch (e) {
      setError(toMessage(e));
    } finally {
      setBusy(false);
    }
  }

  function saveAssignment(e: FormEvent) {
    e.preventDefault();
    void run(() => assignLead(lead.kind, lead.ref, { channel, assigneeLabel: assignee.trim() || undefined, note: note.trim() || undefined }), t("backoffice.lead.assigned"));
  }

  function saveStatus() {
    void run(() => setLeadStatus(lead.kind, lead.ref, { status, note: note.trim() || undefined }), t("backoffice.lead.statusSaved"));
  }

  return (
    <section id="assign" className={CARD}>
      <h2 className="mb-2 text-sm font-semibold text-slate-900">{t("backoffice.lead.assignTitle")}</h2>
      <form onSubmit={saveAssignment} className="space-y-3">
        <label className="block text-xs font-medium text-slate-600">
          {t("backoffice.lead.channel")}
          <select ref={firstField} value={channel} onChange={(e) => setChannel(e.target.value)} className={FIELD}>
            {CHANNELS.map((c) => (
              <option key={c} value={c} disabled={micro && c === "FIELD_SALES"}>
                {t(`backoffice.channel.${c}`)}
              </option>
            ))}
          </select>
        </label>
        {micro && <p className="text-xs text-slate-600">{t("backoffice.lead.microNoField")}</p>}
        <label className="block text-xs font-medium text-slate-600">
          {t("backoffice.lead.assignee")}
          <input value={assignee} onChange={(e) => setAssignee(e.target.value)} maxLength={80} className={FIELD} />
        </label>
        <label className="block text-xs font-medium text-slate-600">
          {t("backoffice.lead.note")}
          <textarea value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} rows={2} className={`${FIELD} py-2`} />
        </label>
        <button type="submit" disabled={busy} className="min-h-10 w-full rounded-lg bg-teal-700 text-sm font-medium text-white hover:bg-teal-800 disabled:opacity-60">
          {t("backoffice.lead.assign")}
        </button>
        <label className="block text-xs font-medium text-slate-600">
          {t("backoffice.lead.status")}
          <select value={status} onChange={(e) => setStatus(e.target.value)} className={FIELD}>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(`backoffice.status.${s}`)}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          onClick={saveStatus}
          disabled={busy}
          className="min-h-10 w-full rounded-lg border border-teal-700 text-sm font-medium text-teal-800 hover:bg-teal-50 disabled:opacity-60"
        >
          {t("backoffice.lead.saveStatus")}
        </button>
        {error && (
          <p role="alert" className="text-sm text-red-800">
            {error}
          </p>
        )}
      </form>
    </section>
  );
}
