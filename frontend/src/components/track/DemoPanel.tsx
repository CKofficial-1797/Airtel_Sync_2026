import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { demoAdvance, getDemoStatus } from "../../api/track";
import type { DemoAdvance, TrackerView } from "../../api/types";

interface Props {
  view: TrackerView;
  onResult: (view: TrackerView) => void;
}

/**
 * For the demo video only: shown with ?demo=1 in the URL AND when the backend says demo mode is on.
 * English on purpose: this is a presenter's tool, never part of the customer's screen.
 */
export function DemoPanel({ view, onResult }: Props) {
  const [params] = useSearchParams();
  const requested = params.get("demo") === "1";
  const [enabled, setEnabled] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!requested) return;
    let cancelled = false;
    getDemoStatus()
      .then((s) => {
        if (!cancelled) setEnabled(s.enabled);
      })
      .catch(() => undefined); // no panel if the status can't be read
    return () => {
      cancelled = true;
    };
  }, [requested]);

  if (!requested || !enabled) {
    return null;
  }

  const run = async (body: DemoAdvance) => {
    setBusy(true);
    setMessage(null);
    try {
      onResult(await demoAdvance(view.orderId, body));
    } catch (e) {
      setMessage(e instanceof ApiError ? e.message : "Request failed");
    } finally {
      setBusy(false);
    }
  };

  const onHold = view.currentStatus === "ON_HOLD";
  const button = "min-h-11 rounded-xl border border-slate-400 bg-white px-3 text-sm font-medium text-slate-900 hover:bg-slate-100 disabled:opacity-50";

  return (
    <section aria-labelledby="demo-heading" className="rounded-2xl border-2 border-dashed border-slate-400 bg-slate-100 p-4" data-testid="demo-panel" lang="en">
      <h2 id="demo-heading" className="font-semibold text-slate-900">Demo controls</h2>
      <p className="mb-2 text-xs text-slate-700">Not part of the product. Status: {view.currentStatus}</p>
      <div className="flex flex-wrap gap-2">
        <button type="button" className={button} disabled={busy || onHold} onClick={() => void run({})}>
          Advance to next step
        </button>
        <button type="button" className={button} disabled={busy || onHold} onClick={() => void run({ onHoldReason: "SITE_NOT_FEASIBLE" })}>
          Put on hold: site not feasible
        </button>
        <button type="button" className={button} disabled={busy || !onHold} onClick={() => void run({})}>
          Resume
        </button>
      </div>
      {message && <p role="alert" className="mt-2 text-sm text-red-800">{message}</p>}
    </section>
  );
}
