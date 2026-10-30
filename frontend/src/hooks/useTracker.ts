import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, NetworkError } from "../api/client";
import { getTracker } from "../api/track";
import type { TrackerView } from "../api/types";

export const AUTO_REFRESH_MS = 30_000;

export type TrackerLoadError = "notFound" | "network" | "generic";

/**
 * The tracker's state. Refreshes itself every 30 s while the tab is visible, and as soon as the owner comes back to it.
 * Only the newest request may update the page, so a slow answer can't undo a slot just booked.
 */
export function useTracker(orderId: string) {
  const [view, setView] = useState<TrackerView | null>(null);
  const [error, setError] = useState<TrackerLoadError | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [checkedAt, setCheckedAt] = useState<Date | null>(null);
  const latest = useRef(0);
  const counted = useRef(false);

  const load = useCallback(
    async (auto: boolean) => {
      const request = ++latest.current;
      try {
        const fresh = await getTracker(orderId, auto);
        if (request === latest.current) {
          setView(fresh);
          setError(null);
          setCheckedAt(new Date());
        }
      } catch (e) {
        if (request === latest.current) {
          if (e instanceof ApiError && e.status === 404) setError("notFound");
          else setError(e instanceof NetworkError ? "network" : "generic");
        }
      }
    },
    [orderId],
  );

  useEffect(() => {
    // One counted view per page open, also under React StrictMode's double effect in development.
    const first = !counted.current;
    counted.current = true;
    void load(!first);
  }, [load]);

  useEffect(() => {
    const refreshIfVisible = () => {
      if (document.visibilityState === "visible") void load(true);
    };
    const timer = window.setInterval(refreshIfVisible, AUTO_REFRESH_MS);
    document.addEventListener("visibilitychange", refreshIfVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshIfVisible);
    };
  }, [load]);

  /** The Refresh button: the owner checking, so it counts. */
  const refresh = useCallback(async () => {
    setRefreshing(true);
    await load(false);
    setRefreshing(false);
  }, [load]);

  /** A fresh tracker returned by an action (booking, demo controls); it beats any GET still on its way. */
  const apply = useCallback((next: TrackerView) => {
    latest.current += 1;
    setView(next);
    setError(null);
    setCheckedAt(new Date());
  }, []);

  return { view, error, refreshing, checkedAt, refresh, apply };
}
