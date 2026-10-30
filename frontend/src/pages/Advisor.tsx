import { Loader2, RotateCcw, SendHorizontal } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { getBusinessTypes } from "../api/advisor";
import type { BusinessTypeRef } from "../api/types";
import { ChatBubble } from "../components/ChatBubble";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { ErrorBanner } from "../components/ErrorBanner";
import { Header } from "../components/Layout";
import { ProfileSummary } from "../components/ProfileSummary";
import { QuickReplies, type Chip } from "../components/QuickReplies";
import { RecommendationCard } from "../components/RecommendationCard";
import { TypingIndicator } from "../components/TypingIndicator";
import { useAdvisorSession } from "../hooks/useAdvisorSession";
import { currentLanguage } from "../i18n";

const MAX_LEN = 1000;
const POPULAR_TYPES = ["Kirana store", "Dental clinic", "CA firm", "Restaurant chain", "Salon", "Textile unit"];
const YES_NO_QUESTION = /^(do|does|did|would|will|is|are|have|has|can|should)\b|\b(क्या)\b|\bkya\b/i;

export function Advisor() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const session = useAdvisorSession(currentLanguage());
  const [text, setText] = useState("");
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [types, setTypes] = useState<BusinessTypeRef[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    getBusinessTypes().then(setTypes).catch(() => setTypes([]));
  }, []);

  useEffect(() => {
    endRef.current?.scrollIntoView?.({ block: "end", behavior: "smooth" });
  }, [session.messages.length, session.status, session.error]);

  const busy = session.status !== "ready";

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (busy || !text.trim()) return;
    void session.send(text);
    setText("");
  };

  const prefill = (value: string) => {
    setText(value);
    inputRef.current?.focus();
  };

  const chips = useMemo<Chip[]>(() => {
    const { stage, quote, recommendation, profile, messages } = session;
    if (stage === "READY_FOR_CHECKOUT" || stage === "CHECKOUT_DONE") return [];
    const say = (label: string, message = label): Chip => ({ label, onSelect: () => void session.send(message) });
    if (quote) {
      const notOffered = recommendation?.excluded[0];
      return [
        say(t("advisor.quick.looksGood"), t("advisor.quick.looksGoodMessage")),
        { label: t("advisor.quick.change"), onSelect: () => prefill(t("advisor.quick.changePrompt")) },
        ...(notOffered
          ? [say(t("advisor.quick.whyNot", { name: notOffered.productName }), t("advisor.quick.whyNotMessage", { name: notOffered.productName }))]
          : []),
      ];
    }
    const lastBot = [...messages].reverse().find((m) => m.role === "assistant")?.content.trim() ?? "";
    const gstUnanswered = !profile.gstin && profile.gstinDeclared === undefined;
    if (lastBot.endsWith("?") && /GST/i.test(lastBot) && gstUnanswered && profile.businessType) {
      return [say(t("advisor.quick.gstYes")), say(t("advisor.quick.gstNo"))];
    }
    if (!profile.businessType && types.length > 0) {
      const known = new Set(types.map((x) => x.businessType));
      const popular = POPULAR_TYPES.filter((x) => known.has(x)).slice(0, 6);
      return [...popular.map((x) => say(x)), { label: t("advisor.quick.other"), onSelect: () => prefill(t("advisor.quick.otherPrompt")) }];
    }
    const lastSentence = lastBot.split(/[.!।\n]/).filter(Boolean).pop()?.trim() ?? "";
    if (lastBot.endsWith("?") && YES_NO_QUESTION.test(lastSentence)) {
      return [say(t("advisor.quick.yes")), say(t("advisor.quick.no"))];
    }
    return [];
  }, [session, types, t]);

  const errorText =
    session.error === "AI_UNAVAILABLE"
      ? t("advisor.aiUnavailable")
      : session.error === "NETWORK"
        ? t("common.networkError")
        : session.error
          ? t("common.genericError")
          : null;

  return (
    <>
      <Header
        title={t("advisor.title")}
        backTo="/"
        actions={
          <button
            type="button"
            onClick={() => setConfirmOpen(true)}
            disabled={session.status === "loading"}
            className="flex min-h-11 items-center gap-1.5 rounded-full px-3 text-sm font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-50"
          >
            <RotateCcw className="size-4" aria-hidden />
            {t("advisor.startOver")}
          </button>
        }
      />
      <ProfileSummary profile={session.profile} resolved={session.recommendation?.resolvedProfile} />

      <main id="main" className="flex flex-1 flex-col overflow-hidden">
        {session.status === "loading" ? (
          <div className="flex flex-1 items-center justify-center gap-2 text-slate-600" role="status">
            <Loader2 className="size-5 animate-spin" aria-hidden />
            {t("advisor.starting")}
          </div>
        ) : session.status === "fatal" ? (
          <div className="flex flex-1 flex-col justify-center py-8">
            <ErrorBanner message={errorText ?? t("common.genericError")} onRetry={() => void session.reload()} />
          </div>
        ) : (
          <div className="flex-1 space-y-3 overflow-y-auto px-3 py-4" aria-live="polite" aria-relevant="additions">
            {session.messages.map((m) => (
              <div key={m.id} className="space-y-3">
                <ChatBubble role={m.role} content={m.content} failed={m.failed} />
                {m.id === session.cardAfter && session.quote && (
                  <RecommendationCard
                    recommendation={session.recommendation}
                    quote={session.quote}
                    stage={session.stage}
                    onOpenQuote={() => navigate("/quote")}
                  />
                )}
              </div>
            ))}
            {session.status === "sending" && <TypingIndicator />}
            {/* scroll margin keeps the newest message above the sticky input bar */}
            <div ref={endRef} className="h-px scroll-mb-56" />
          </div>
        )}
      </main>

      {session.status !== "loading" && session.status !== "fatal" && (
        <div className="sticky bottom-0 border-t border-slate-200 bg-white pb-[max(env(safe-area-inset-bottom),0.5rem)] pt-2">
          {errorText && (
            <div className="pb-2">
              <ErrorBanner
                message={errorText}
                busy={session.status === "sending"}
                onRetry={session.failedMessageId ? () => void session.retry() : undefined}
                onDismiss={session.dismissError}
              />
            </div>
          )}
          <QuickReplies chips={chips} disabled={busy} />
          <form onSubmit={submit} className="flex items-end gap-2 px-3">
            <div className="flex-1">
              <label htmlFor="chat-input" className="sr-only">
                {t("advisor.inputLabel")}
              </label>
              <input
                id="chat-input"
                ref={inputRef}
                value={text}
                maxLength={MAX_LEN}
                onChange={(e) => setText(e.target.value)}
                placeholder={t("advisor.inputPlaceholder")}
                autoComplete="off"
                enterKeyHint="send"
                className="h-12 w-full rounded-2xl border border-slate-300 bg-slate-50 px-4 text-[16px] text-slate-900 placeholder:text-slate-500 focus:border-teal-700 focus:outline-none focus:ring-2 focus:ring-teal-700/30"
              />
              {text.length > MAX_LEN - 100 && (
                <p className="mt-1 text-xs text-slate-600">{t("advisor.charsLeft", { count: MAX_LEN - text.length })}</p>
              )}
            </div>
            <button
              type="submit"
              disabled={busy || !text.trim()}
              className="flex size-12 shrink-0 items-center justify-center rounded-2xl bg-teal-700 text-white hover:bg-teal-800 disabled:opacity-50"
              aria-label={t("advisor.send")}
            >
              {session.status === "sending" ? (
                <Loader2 className="size-5 animate-spin" aria-hidden />
              ) : (
                <SendHorizontal className="size-5" aria-hidden />
              )}
            </button>
          </form>
        </div>
      )}

      <ConfirmDialog
        open={confirmOpen}
        title={t("advisor.startOverTitle")}
        body={t("advisor.startOverBody")}
        confirmLabel={t("advisor.startOverConfirm")}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => {
          setConfirmOpen(false);
          setText("");
          void session.startOver();
        }}
      />
    </>
  );
}
