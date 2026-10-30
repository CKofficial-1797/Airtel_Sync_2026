import { useEffect, useState } from "react";
import { getSession } from "../api/advisor";
import { NetworkError } from "../api/client";
import type { SessionView } from "../api/types";
import { KEYS, readStorage } from "../lib/storage";

export type SnapshotState =
  | { status: "loading" }
  | { status: "empty" }
  | { status: "error"; network: boolean }
  | { status: "ready"; session: SessionView };

/** Loads the saved advisor session once (for pages opened directly, e.g. /quote after a reload). */
export function useSessionSnapshot(): SnapshotState {
  const [state, setState] = useState<SnapshotState>({ status: "loading" });

  useEffect(() => {
    const id = readStorage(KEYS.sessionId);
    if (!id) {
      setState({ status: "empty" });
      return;
    }
    let cancelled = false;
    getSession(id)
      .then((session) => !cancelled && setState(session.quote ? { status: "ready", session } : { status: "empty" }))
      .catch((e: unknown) => !cancelled && setState({ status: "error", network: e instanceof NetworkError }));
    return () => {
      cancelled = true;
    };
  }, []);

  return state;
}
