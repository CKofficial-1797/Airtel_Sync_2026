import { PackageSearch } from "lucide-react";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { useParams } from "react-router-dom";
import { getOrder } from "../api/orders";
import { Header } from "../components/Layout";

/** Phase 8 replaces this. /track/:orderId shows the order ref; plain /track (from the landing page) has none. */
export function TrackPlaceholder() {
  const { t } = useTranslation();
  const { orderId } = useParams();
  const [orderRef, setOrderRef] = useState<string | null>(null);

  useEffect(() => {
    if (!orderId) return;
    getOrder(orderId)
      .then((order) => setOrderRef(order.orderRef))
      .catch(() => setOrderRef(null));
  }, [orderId]);

  return (
    <>
      <Header title={t("track.title")} backTo="/" />
      <main id="main" className="flex flex-1 flex-col items-center justify-center gap-3 p-8 text-center">
        <PackageSearch className="size-12 text-teal-700" aria-hidden />
        {orderRef && (
          <p className="text-sm text-slate-600">
            {t("kyc.orderRef")}: <span className="font-mono font-semibold text-slate-900">{orderRef}</span>
          </p>
        )}
        <p className="text-slate-700">{t("track.body")}</p>
      </main>
    </>
  );
}
