import { BadgeCheck, FileCheck2, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router-dom";
import { getOrder } from "../api/orders";
import type { OrderView } from "../api/types";
import { Header } from "../components/Layout";
import { formatInr } from "../lib/format";

/** Phase 5 placeholder: proves the order exists. Phase 7 replaces it with the KYC screens (same route). */
export function KycPlaceholder() {
  const { t } = useTranslation();
  const { orderId = "" } = useParams();
  const [order, setOrder] = useState<OrderView | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    getOrder(orderId)
      .then(setOrder)
      .catch(() => setFailed(true));
  }, [orderId]);

  return (
    <>
      <Header title={t("kyc.title")} backTo="/" />
      <main id="main" className="flex flex-1 flex-col items-center px-6 py-10 text-center">
        {failed ? (
          <p role="alert" className="text-red-700">
            {t("kyc.notFound")}
          </p>
        ) : !order ? (
          <p className="flex items-center gap-2 text-slate-600" role="status">
            <Loader2 className="size-5 animate-spin" aria-hidden />
            {t("common.loading")}
          </p>
        ) : (
          <>
            <BadgeCheck className="size-14 text-green-700" aria-hidden />
            <h2 className="mt-3 text-xl font-semibold text-slate-900">{t("kyc.title")}</h2>
            <dl className="mt-6 w-full space-y-3 rounded-2xl border border-slate-200 bg-white p-5 text-left">
              <div>
                <dt className="text-xs text-slate-600">{t("kyc.orderRef")}</dt>
                <dd className="font-mono text-lg font-semibold text-slate-900" data-testid="order-ref">
                  {order.orderRef}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-slate-600">{t("kyc.monthly")}</dt>
                <dd className="text-lg font-semibold text-slate-900 tabular-nums">{formatInr(order.quote.monthlyTotalInr)}</dd>
              </div>
            </dl>
            <div className="mt-6 flex items-start gap-3 rounded-2xl bg-teal-50 p-4 text-left text-sm text-teal-900">
              <FileCheck2 className="mt-0.5 size-5 shrink-0" aria-hidden />
              <p>
                <strong className="block">{t("kyc.comingNext")}</strong>
                {t("kyc.body")}
              </p>
            </div>
          </>
        )}
      </main>
    </>
  );
}
