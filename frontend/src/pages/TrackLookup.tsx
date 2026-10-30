import { Loader2, Search } from "lucide-react";
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { ApiError, NetworkError } from "../api/client";
import { lookupOrder } from "../api/track";
import { LanguageToggle } from "../components/LanguageToggle";
import { Header } from "../components/Layout";

const ORDER_REF = /^UD-\d{6}-\d{4,}$/;
const PINCODE = /^\d{6}$/;

/** /track: find an order without the link, from its reference and installation PIN code. */
export function TrackLookup() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [orderRef, setOrderRef] = useState("");
  const [pincode, setPincode] = useState("");
  const [refError, setRefError] = useState(false);
  const [pinError, setPinError] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [searching, setSearching] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const ref = orderRef.trim().toUpperCase();
    const pin = pincode.trim();
    const badRef = !ORDER_REF.test(ref);
    const badPin = !PINCODE.test(pin);
    setRefError(badRef);
    setPinError(badPin);
    setFailure(null);
    if (badRef || badPin) return;

    setSearching(true);
    try {
      const { orderId } = await lookupOrder(ref, pin);
      navigate(`/track/${orderId}`);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setFailure(t("track.lookup.notFound"));
      else if (err instanceof ApiError && err.status === 429) setFailure(t("track.lookup.rateLimited"));
      else setFailure(err instanceof NetworkError ? t("common.networkError") : t("common.genericError"));
      setSearching(false);
    }
  };

  const input = "mt-1 min-h-12 w-full rounded-xl border bg-white px-3 text-slate-900";

  return (
    <>
      <Header title={t("track.title")} backTo="/" actions={<LanguageToggle />} />
      <main id="main" className="flex-1 px-3 py-4">
        <form onSubmit={(e) => void submit(e)} noValidate className="space-y-4 rounded-2xl border border-slate-200 bg-white p-4">
          <div>
            <h2 className="font-semibold text-slate-900">{t("track.lookup.heading")}</h2>
            <p className="text-sm text-slate-600">{t("track.lookup.body")}</p>
          </div>
          <div>
            <label htmlFor="order-ref" className="text-sm font-medium text-slate-900">{t("track.lookup.orderRef")}</label>
            <input
              id="order-ref"
              value={orderRef}
              onChange={(e) => setOrderRef(e.target.value)}
              placeholder="UD-260715-0042"
              autoCapitalize="characters"
              autoComplete="off"
              aria-invalid={refError}
              aria-describedby={refError ? "order-ref-error" : undefined}
              className={`${input} font-mono ${refError ? "border-red-600" : "border-slate-300"}`}
            />
            {refError && <p id="order-ref-error" className="mt-1 text-sm text-red-700">{t("track.lookup.orderRefError")}</p>}
          </div>
          <div>
            <label htmlFor="pincode" className="text-sm font-medium text-slate-900">{t("track.lookup.pincode")}</label>
            <input
              id="pincode"
              value={pincode}
              onChange={(e) => setPincode(e.target.value)}
              inputMode="numeric"
              maxLength={6}
              autoComplete="postal-code"
              aria-invalid={pinError}
              aria-describedby={pinError ? "pincode-error" : undefined}
              className={`${input} ${pinError ? "border-red-600" : "border-slate-300"}`}
            />
            {pinError && <p id="pincode-error" className="mt-1 text-sm text-red-700">{t("track.lookup.pincodeError")}</p>}
          </div>
          {failure && <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">{failure}</p>}
          <button
            type="submit"
            disabled={searching}
            className="flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-teal-700 px-4 font-semibold text-white hover:bg-teal-800 disabled:opacity-60"
          >
            {searching ? <Loader2 className="size-4 animate-spin" aria-hidden /> : <Search className="size-4" aria-hidden />}
            {t("track.lookup.submit")}
          </button>
          <p className="text-xs text-slate-600">{t("track.lookup.hint")}</p>
        </form>
      </main>
    </>
  );
}
