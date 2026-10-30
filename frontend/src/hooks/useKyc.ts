import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, ConsentRequiredError, NetworkError } from "../api/client";
import * as api from "../api/kyc";
import type { ConsentRequest, DocType, KycView } from "../api/types";
import { withDocument, withSubmitResult } from "../lib/kyc";

export type KycLoadError = "notFound" | "network" | "generic";

/** Consent gone, or a verdict already stands (e.g. from another tab): the page should catch up with the server. */
function stateMovedOn(e: unknown): boolean {
  return e instanceof ConsentRequiredError || (e instanceof ApiError && e.status === 409);
}

/** All KYC state lives on the server; this hook mirrors GET /kyc, so a reload resumes at the right step. */
export function useKyc(orderId: string) {
  const [view, setView] = useState<KycView | null>(null);
  const [loadError, setLoadError] = useState<KycLoadError | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<"network" | "generic" | null>(null);
  // Bumped by every change we apply: a GET that started before a change has an older answer, so it is dropped.
  const changes = useRef(0);

  const load = useCallback(async () => {
    const startedAt = changes.current;
    setLoadError(null);
    try {
      const fresh = await api.getKyc(orderId);
      if (changes.current === startedAt) setView(fresh);
    } catch (e) {
      if (changes.current !== startedAt) return;
      if (e instanceof ApiError && e.status === 404) setLoadError("notFound");
      else setLoadError(e instanceof NetworkError ? "network" : "generic");
    }
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  const consent = useCallback(
    async (body: ConsentRequest) => {
      const next = await api.giveConsent(orderId, body);
      changes.current += 1;
      setView(next);
    },
    [orderId],
  );

  /** Failures reach the slot unchanged; a lost consent also refreshes the page back to the consent step. */
  const upload = useCallback(
    async (docType: DocType, file: File) => {
      try {
        const doc = await api.uploadDocument(orderId, docType, file);
        changes.current += 1;
        setView((v) => (v ? withDocument(v, doc) : v));
      } catch (e) {
        if (stateMovedOn(e)) await load();
        throw e;
      }
    },
    [orderId, load],
  );

  const submit = useCallback(async () => {
    setSubmitting(true);
    setSubmitError(null);
    try {
      const result = await api.submitKyc(orderId);
      changes.current += 1;
      setView((v) => (v ? withSubmitResult(v, result) : v));
    } catch (e) {
      if (stateMovedOn(e)) await load();
      else setSubmitError(e instanceof NetworkError ? "network" : "generic");
    } finally {
      setSubmitting(false);
    }
  }, [orderId, load]);

  const withdraw = useCallback(async () => {
    const next = await api.withdrawConsent(orderId);
    changes.current += 1;
    setView(next);
  }, [orderId]);

  return { view, loadError, reload: load, consent, upload, submit, submitting, submitError, withdraw };
}
