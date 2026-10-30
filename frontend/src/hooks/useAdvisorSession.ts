import { useCallback, useEffect, useRef, useState } from "react";
import { createSession, getSession, sendMessage } from "../api/advisor";
import { AiUnavailableError, ApiError, NetworkError } from "../api/client";
import type { Profile, Quote, Recommendation, SessionView, Stage } from "../api/types";
import type { Language } from "../i18n";
import { KEYS, readStorage, removeStorage, writeStorage } from "../lib/storage";

export interface UiMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  failed?: boolean;
}

export type AdvisorError = "AI_UNAVAILABLE" | "NETWORK" | "GENERIC";

export interface AdvisorState {
  status: "loading" | "ready" | "sending" | "fatal";
  sessionId: string | null;
  messages: UiMessage[];
  stage: Stage;
  profile: Profile;
  recommendation: Recommendation | null;
  quote: Quote | null;
  /** The bundle card is shown right after this message (the reply that last changed the bundle). */
  cardAfter: string | null;
  error: AdvisorError | null;
  failedMessageId: string | null;
}

const INITIAL: AdvisorState = {
  status: "loading",
  sessionId: null,
  messages: [],
  stage: "COLLECTING",
  profile: {},
  recommendation: null,
  quote: null,
  cardAfter: null,
  error: null,
  failedMessageId: null,
};

let counter = 0;
const nextId = () => `m${++counter}`; // crypto.randomUUID is unavailable on plain-http LAN testing

function bundleKey(quote: Quote | null): string {
  return quote ? `${quote.monthlyTotalInr}|${quote.lines.map((l) => `${l.productId}x${l.quantity}`).join(",")}` : "";
}

function errorKind(e: unknown): AdvisorError {
  if (e instanceof AiUnavailableError) return "AI_UNAVAILABLE";
  if (e instanceof NetworkError) return "NETWORK";
  return "GENERIC";
}

export function fromSession(s: SessionView): AdvisorState {
  const messages: UiMessage[] = s.messages.map((m) => ({ id: nextId(), role: m.role, content: m.content }));
  const lastBot = [...messages].reverse().find((m) => m.role === "assistant");
  return {
    ...INITIAL,
    status: "ready",
    sessionId: s.sessionId,
    messages,
    stage: s.stage,
    profile: s.profile ?? {},
    recommendation: s.recommendation,
    quote: s.quote,
    cardAfter: s.quote && lastBot ? lastBot.id : null,
  };
}

export function useAdvisorSession(language: Language) {
  const [state, setState] = useState<AdvisorState>(INITIAL);
  const stateRef = useRef(state);
  stateRef.current = state;
  const started = useRef(false);

  const startNew = useCallback(async () => {
    try {
      const created = await createSession(language);
      writeStorage(KEYS.sessionId, created.sessionId);
      setState({
        ...INITIAL,
        status: "ready",
        sessionId: created.sessionId,
        messages: [{ id: nextId(), role: "assistant", content: created.greeting }],
      });
    } catch (e) {
      setState({ ...INITIAL, status: "fatal", error: errorKind(e) });
    }
  }, [language]);

  const load = useCallback(async () => {
    setState({ ...INITIAL });
    const saved = readStorage(KEYS.sessionId);
    if (saved) {
      try {
        setState(fromSession(await getSession(saved)));
        return;
      } catch (e) {
        const gone = e instanceof ApiError && (e.status === 404 || e.status === 400);
        if (!gone) {
          setState({ ...INITIAL, status: "fatal", error: errorKind(e) });
          return;
        }
        removeStorage(KEYS.sessionId);
      }
    }
    await startNew();
  }, [startNew]);

  useEffect(() => {
    if (started.current) return; // StrictMode runs effects twice in dev; create only one session
    started.current = true;
    void load();
  }, [load]);

  const deliver = useCallback(async (text: string, messageId: string) => {
    const { sessionId } = stateRef.current;
    if (!sessionId) return;
    try {
      const reply = await sendMessage(sessionId, text);
      const botId = nextId();
      setState((s) => {
        const changed = reply.quote !== null && bundleKey(reply.quote) !== bundleKey(s.quote);
        return {
          ...s,
          status: "ready",
          error: null,
          failedMessageId: null,
          messages: [...s.messages, { id: botId, role: "assistant", content: reply.reply }],
          stage: reply.stage,
          profile: reply.profile ?? s.profile,
          recommendation: reply.recommendation,
          quote: reply.quote,
          cardAfter: changed ? botId : s.cardAfter,
        };
      });
    } catch (e) {
      setState((s) => ({
        ...s,
        status: "ready",
        error: errorKind(e),
        failedMessageId: messageId,
        messages: s.messages.map((m) => (m.id === messageId ? { ...m, failed: true } : m)),
      }));
    }
  }, []);

  /** Sends a new message. The text stays visible in the chat even if delivery fails. */
  const send = useCallback(
    async (raw: string) => {
      const text = raw.trim().slice(0, 1000);
      if (!text || stateRef.current.status !== "ready") return;
      const id = nextId();
      setState((s) => ({
        ...s,
        status: "sending",
        error: null,
        failedMessageId: null,
        messages: [...s.messages, { id, role: "user", content: text }],
      }));
      await deliver(text, id);
    },
    [deliver],
  );

  /** Re-sends the message that failed (same bubble, no duplicate). */
  const retry = useCallback(async () => {
    const { failedMessageId, messages, status } = stateRef.current;
    const failed = messages.find((m) => m.id === failedMessageId);
    if (!failed || status !== "ready") return;
    setState((s) => ({
      ...s,
      status: "sending",
      error: null,
      messages: s.messages.map((m) => (m.id === failed.id ? { ...m, failed: false } : m)),
    }));
    await deliver(failed.content, failed.id);
  }, [deliver]);

  const dismissError = useCallback(() => setState((s) => ({ ...s, error: null })), []);

  const startOver = useCallback(async () => {
    removeStorage(KEYS.sessionId);
    setState({ ...INITIAL });
    await startNew();
  }, [startNew]);

  return { ...state, send, retry, dismissError, startOver, reload: load };
}
