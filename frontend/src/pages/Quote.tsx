import { Info, Loader2, MessageCircle, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router-dom";
import { NetworkError, QuoteChangedError } from "../api/client";
import { createOrder } from "../api/orders";
import { getProducts } from "../api/quote";
import type { Product, Profile, Quote as QuoteJson, Recommendation, SessionView } from "../api/types";
import { AddOnToggle } from "../components/AddOnToggle";
import { ErrorBanner } from "../components/ErrorBanner";
import { IneligibleList } from "../components/IneligibleList";
import { Header } from "../components/Layout";
import { ProfileSummary } from "../components/ProfileSummary";
import { QuoteLineItem } from "../components/QuoteLineItem";
import { TotalsBar } from "../components/TotalsBar";
import { useQuote } from "../hooks/useQuote";
import { useSessionSnapshot } from "../hooks/useSessionSnapshot";
import { formatInr } from "../lib/format";

const ADD_ONS_SHOWN = 5;

/** The engine's DEPENDENCY_REMOVED message already reads "X removed because Y was removed": don't repeat it. */
function removedText(name: string, reason: string, t: (key: string, opts?: Record<string, string>) => string): string {
  return reason.startsWith(`${name} removed`) ? reason : t("quote.removedNotice", { name, reason });
}

export function Quote() {
  const { t } = useTranslation();
  const snapshot = useSessionSnapshot();

  if (snapshot.status === "loading") {
    return (
      <>
        <Header title={t("quote.title")} backTo="/advisor" />
        <div className="flex flex-1 items-center justify-center gap-2 text-slate-600" role="status">
          <Loader2 className="size-5 animate-spin" aria-hidden />
          {t("common.loading")}
        </div>
      </>
    );
  }
  if (snapshot.status === "error") {
    return (
      <>
        <Header title={t("quote.title")} backTo="/advisor" />
        <div className="py-8">
          <ErrorBanner
            message={snapshot.network ? t("common.networkError") : t("common.genericError")}
            onRetry={() => window.location.reload()}
          />
        </div>
      </>
    );
  }
  if (snapshot.status === "empty") {
    return (
      <>
        <Header title={t("quote.title")} backTo="/" />
        <main id="main" className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
          <MessageCircle className="size-10 text-teal-700" aria-hidden />
          <h2 className="text-lg font-semibold text-slate-900">{t("quote.emptyTitle")}</h2>
          <p className="text-slate-600">{t("quote.emptyBody")}</p>
          <Link to="/advisor" className="mt-2 flex min-h-12 items-center rounded-xl bg-teal-700 px-5 font-semibold text-white">
            {t("quote.emptyCta")}
          </Link>
        </main>
      </>
    );
  }
  return <QuoteEditor session={snapshot.session} />;
}

function QuoteEditor({ session }: { session: SessionView }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const profile: Profile = session.profile ?? {};
  const recommendation: Recommendation | null = session.recommendation;
  const q = useQuote(profile, session.quote as QuoteJson);
  const [descriptions, setDescriptions] = useState<Map<string, string>>(new Map());
  const [addedIds, setAddedIds] = useState<Set<string>>(new Set());
  const [showAllAddOns, setShowAllAddOns] = useState(false);
  const [placing, setPlacing] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);

  useEffect(() => {
    getProducts()
      .then((products: Product[]) => setDescriptions(new Map(products.map((p) => [p.productId, p.description]))))
      .catch(() => undefined); // descriptions are optional
  }, []);

  const quote = q.quote;
  const qty = useMemo(() => new Map(q.items.map((i) => [i.productId, i.quantity])), [q.items]);
  const upgrades = useMemo(
    () => new Map((recommendation?.optionalAddOns ?? []).filter((l) => l.replaces).map((l) => [l.productId, l.replaces])),
    [recommendation],
  );
  const recommendedIds = useMemo(() => (recommendation?.optionalAddOns ?? []).map((l) => l.productId), [recommendation]);

  if (!quote) return null;

  const lines = quote.lines.filter((l) => qty.has(l.productId));
  const included = lines.filter((l) => !addedIds.has(l.productId));
  const addedLines = lines.filter((l) => addedIds.has(l.productId));
  const offAddOns = [...quote.availableAddOns].sort((a, b) => {
    const ia = recommendedIds.indexOf(a.productId);
    const ib = recommendedIds.indexOf(b.productId);
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
  });
  const visibleOff = showAllAddOns ? offAddOns : offAddOns.slice(0, ADD_ONS_SHOWN);

  const toggle = (productId: string, defaultQuantity: number, on: boolean) => {
    setAddedIds((s) => {
      const next = new Set(s);
      if (on) next.add(productId);
      else next.delete(productId);
      return next;
    });
    q.toggleAddOn({ productId, defaultQuantity }, on, upgrades.get(productId));
  };

  const accept = async () => {
    setPlacing(true);
    setBanner(null);
    try {
      const order = await createOrder(session.sessionId, profile, q.items);
      navigate(`/kyc/${order.orderId}`, { state: { orderRef: order.orderRef } });
    } catch (e) {
      if (e instanceof QuoteChangedError) {
        q.replaceQuote(e.quote);
        setBanner(t("quote.quoteChanged"));
      } else {
        setBanner(e instanceof NetworkError ? t("common.networkError") : t("common.genericError"));
      }
      setPlacing(false);
    }
  };

  return (
    <>
      <Header title={t("quote.title")} backTo="/advisor" />
      <ProfileSummary
        profile={profile}
        resolved={recommendation?.resolvedProfile}
        action={
          <Link to="/advisor" className="flex min-h-11 shrink-0 items-center px-2 text-sm font-medium text-teal-800 underline">
            {t("common.editDetails")}
          </Link>
        }
      />

      <main id="main" className="flex-1 space-y-5 px-3 py-4">
        {banner && <ErrorBanner message={banner} onDismiss={() => setBanner(null)} />}
        {q.error && (
          <ErrorBanner message={q.error === "NETWORK" ? t("common.networkError") : t("common.genericError")} />
        )}

        {q.notices.map((n) => (
          <div key={n.productId} role="status" className="flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-100 p-3 text-sm text-amber-900">
            <Info className="mt-0.5 size-4 shrink-0" aria-hidden />
            <p className="flex-1">{removedText(n.productName, n.reasons[0]?.message ?? "", t)}</p>
            <button
              type="button"
              onClick={() => q.dismissNotice(n.productId)}
              className="flex size-8 items-center justify-center rounded-lg hover:bg-amber-200"
              aria-label={t("common.dismiss")}
            >
              <X className="size-4" aria-hidden />
            </button>
          </div>
        ))}

        <section aria-labelledby="included-heading">
          <h2 id="included-heading" className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-600">
            {t("quote.included")}
          </h2>
          <ul className="space-y-3">
            {included.map((line) => (
              <QuoteLineItem
                key={line.productId}
                line={line}
                quantity={qty.get(line.productId) ?? line.quantity}
                description={descriptions.get(line.productId)}
                canRemove={q.items.length > 1}
                onQuantity={(v) => q.setQuantity(line.productId, v)}
                onRemove={() => q.removeItem(line.productId)}
              />
            ))}
          </ul>
        </section>

        {(addedLines.length > 0 || offAddOns.length > 0) && (
          <section aria-labelledby="addons-heading">
            <h2 id="addons-heading" className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-600">
              {t("quote.addOns")}
            </h2>
            <ul className="space-y-2">
              {addedLines.map((line) => (
                <AddOnToggle
                  key={line.productId}
                  name={line.productName}
                  description={descriptions.get(line.productId)}
                  unitPriceInr={line.unitPriceInr}
                  unit={line.unit}
                  monthlyTotalInr={line.monthlyTotalInr}
                  on
                  disabled={q.items.length <= 1}
                  onToggle={(on) => toggle(line.productId, line.quantity, on)}
                />
              ))}
              {visibleOff.map((addOn) => (
                <AddOnToggle
                  key={addOn.productId}
                  name={addOn.productName}
                  description={descriptions.get(addOn.productId)}
                  unitPriceInr={addOn.unitPriceInr}
                  unit={addOn.unit}
                  on={false}
                  onToggle={(on) => toggle(addOn.productId, addOn.defaultQuantity, on)}
                />
              ))}
            </ul>
            {offAddOns.length > ADD_ONS_SHOWN && (
              <button
                type="button"
                onClick={() => setShowAllAddOns((v) => !v)}
                className="mt-2 min-h-11 px-1 text-sm font-medium text-teal-800 underline"
                aria-expanded={showAllAddOns}
              >
                {showAllAddOns ? t("quote.showLess") : t("quote.showMore", { count: offAddOns.length - ADD_ONS_SHOWN })}
              </button>
            )}
          </section>
        )}

        <IneligibleList items={quote.ineligibleProducts} />

        {quote.comparison && (
          <p className="rounded-2xl border border-slate-200 bg-white p-4 text-sm text-slate-700">
            {t("quote.comparison", {
              current: formatInr(quote.comparison.currentSpendInr),
              next: formatInr(quote.comparison.newMonthlyInr),
            })}
          </p>
        )}
      </main>

      <TotalsBar quote={quote} updating={q.updating} placing={placing} onAccept={() => void accept()} />
    </>
  );
}
