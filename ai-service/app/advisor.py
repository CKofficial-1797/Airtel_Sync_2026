"""One advisor turn: plain tool-calling loop + grounding guard + deterministic stage."""
from __future__ import annotations

import logging
import re
import time

from app import guard
from app.backend_client import BackendClient
from app.config import Settings
from app.llm import Llm, ModelTurn, SystemNote, ToolResults, UserText
from app.models import ChatRequest, ChatResponse, GuardResult, ToolTraceEntry
from app.prompts import build_system_prompt
from app.reference import ReferenceProvider
from app.tools import TOOL_DECLARATIONS, ToolContext, gstin_answered, missing_required

log = logging.getLogger("advisor")

_ACCEPT = re.compile(
    r"\b(yes|yeah|yep|yup|ok|okay|sure|go ahead|proceed|confirm(?:ed)?|done|let'?s do it|sounds good|book it|"
    r"finali[sz]e|theek hai|thik hai|theek h|haan|haa|han ji|ha ji|haan ji|chalo|kar do|aage badho)\b"
    r"|हाँ|हां|ठीक है|आगे बढ़|कर दो|कर दीजिए|बढ़िया",
    re.IGNORECASE)
_NOT_ACCEPT = re.compile(
    r"\b(no|not|don'?t|nahi|nahin|change|remove|add|instead|but|why|what|how)\b|नहीं|बदल|हटा|जोड़|\?",
    re.IGNORECASE)

MESSAGES = {
    "backend_down": {
        "en": "Sorry, I couldn't fetch prices right now. Please try again in a moment.",
        "hi": "माफ़ कीजिए, अभी मैं कीमतें नहीं ला पाया। कृपया थोड़ी देर बाद फिर कोशिश करें।",
        "hinglish": "Sorry, abhi prices fetch nahi ho paaye. Thodi der baad dobara try kijiye.",
    },
    "loop_limit": {
        "en": "Sorry, I need a bit more detail to work that out. Could you say again what you'd like to change?",
        "hi": "माफ़ कीजिए, मुझे थोड़ी और जानकारी चाहिए। आप क्या बदलना चाहते हैं, फिर से बताइए?",
        "hinglish": "Sorry, mujhe thodi aur detail chahiye. Aap kya change karna chahte hain, phir se batayiye?",
    },
}

_HINGLISH_MARKERS = {"hai", "hain", "mein", "mera", "meri", "mere", "bhai", "nahi", "nahin", "kya", "chahiye",
                     "log", "haan", "theek", "aur", "hum", "humara", "hamara", "dukaan", "dukan", "ka", "ki",
                     "ke", "karo", "kar", "batao", "kitna", "wala", "wali", "accha", "acha", "ji"}
# (language code, first code point, last code point) of each Indic script block
_SCRIPTS = [("hi", 0x0900, 0x097F), ("bn", 0x0980, 0x09FF), ("pa", 0x0A00, 0x0A7F), ("gu", 0x0A80, 0x0AFF),
            ("or", 0x0B00, 0x0B7F), ("ta", 0x0B80, 0x0BFF), ("te", 0x0C00, 0x0C7F), ("kn", 0x0C80, 0x0CFF),
            ("ml", 0x0D00, 0x0D7F)]


def detect_language(text: str) -> str:
    """hi (Devanagari) / other Indic script code / hinglish (Roman Hindi) / en."""
    counts = {code: sum(lo <= ord(ch) <= hi for ch in text) for code, lo, hi in _SCRIPTS}
    code, n = max(counts.items(), key=lambda kv: kv[1])
    if n >= 2:
        return code
    words = re.findall(r"[a-z]+", text.lower())
    if sum(w in _HINGLISH_MARKERS for w in words) >= 2:
        return "hinglish"
    return "en"


def is_acceptance(text: str) -> bool:
    t = text.strip()
    return len(t) <= 60 and bool(_ACCEPT.search(t)) and not _NOT_ACCEPT.search(t)


class Advisor:
    def __init__(self, llm: Llm, backend: BackendClient, settings: Settings,
                 reference: ReferenceProvider | None = None):
        self.llm = llm
        self.backend = backend
        self.settings = settings
        self.reference = reference or ReferenceProvider(backend)

    def chat(self, req: ChatRequest) -> ChatResponse:
        started = time.perf_counter()
        ref = self.reference.get()
        state = req.state
        prev_stage = state.stage if state and state.stage else "COLLECTING"
        ctx = ToolContext(self.backend, ref, req.profile,
                          state.recommendation if state else None, state.quote if state else None)
        user_text = next((m.content for m in reversed(req.messages) if m.role == "user"), "")
        language = detect_language(user_text)

        system = build_system_prompt(ref, ctx.profile, prev_stage, missing_required(ctx.profile, ref),
                                     gstin_answered(ctx.profile), ctx.current_items(), language)
        history = self._history(req)

        reply = self._run_loop(history, ctx, system, self.settings.max_tool_iterations)
        guard_result = GuardResult(passed=True)

        if ctx.backend_failed:
            reply = self._msg("backend_down", language)
            stage = prev_stage
        else:
            if reply is None:
                reply = self._msg("loop_limit", language)
            violations = self._check(reply, ctx)
            if violations:
                history.append(ModelTurn(text=reply))
                history.append(SystemNote(
                    "Your previous reply was NOT shown to the customer because it contained values not found "
                    "in tool results: " + "; ".join(violations) + ". Rewrite the reply using ONLY products and "
                    "exact rupee amounts that appear in tool results. Do not mention any other amount."))
                retry = self._run_loop(history, ctx, system, 2)
                retry_violations = self._check(retry, ctx) if retry else ["no reply"]
                if retry and not retry_violations and not ctx.backend_failed:
                    reply = retry
                    guard_result = GuardResult(passed=True, retries=1, violations=violations)
                else:
                    reply = guard.render_template("hi" if language == "hi" else "en", ctx.quote)
                    guard_result = GuardResult(passed=False, retries=1, fallback_used=True,
                                               violations=violations + retry_violations)
            stage = self._stage(prev_stage, ctx, user_text)

        log.info("session=%s tools=%s stage=%s guard=%s retries=%d fallback=%s ms=%d",
                 req.session_id[:8], [t["tool"] for t in ctx.trace], stage, guard_result.passed,
                 guard_result.retries, guard_result.fallback_used, int((time.perf_counter() - started) * 1000))
        return ChatResponse(
            reply=reply, detected_language=language, profile=ctx.profile, stage=stage,
            recommendation=ctx.recommendation, quote=ctx.quote,
            tool_trace=[ToolTraceEntry(**t) for t in ctx.trace], guard=guard_result)

    # ------------------------------------------------------------------
    def _run_loop(self, history: list, ctx: ToolContext, system: str, max_iterations: int) -> str | None:
        """Up to max_iterations model calls; returns the final text, or None if the limit is hit."""
        for _ in range(max_iterations):
            turn = self.llm.generate(system, history, TOOL_DECLARATIONS)
            history.append(ModelTurn(text=turn.text, function_calls=turn.function_calls, raw=turn.raw))
            if not turn.function_calls:
                return turn.text or None
            results = [(fc.name, fc.id, ctx.dispatch(fc.name, fc.args)) for fc in turn.function_calls]
            history.append(ToolResults(results))
            if ctx.backend_failed:
                return None
        return None

    def _check(self, reply: str, ctx: ToolContext) -> list[str]:
        return guard.check(reply, ctx.payloads, ctx.eligibility_results, ctx.profile, ctx.ref)

    @staticmethod
    def _stage(prev: str, ctx: ToolContext, user_text: str) -> str:
        if ctx.last_bundle_event == "quote":
            return "CUSTOMISING"
        if ctx.last_bundle_event == "recommend":
            return "RECOMMENDED"
        has_bundle = bool(ctx.recommendation or ctx.quote)
        if has_bundle and is_acceptance(user_text):
            return "READY_FOR_CHECKOUT"
        if ctx.off_topic and not any(t["tool"] != "mark_off_topic" for t in ctx.trace):
            return "OUT_OF_SCOPE"
        if prev == "OUT_OF_SCOPE":
            return "RECOMMENDED" if has_bundle else "COLLECTING"
        return prev

    @staticmethod
    def _history(req: ChatRequest) -> list:
        messages = list(req.messages)
        while messages and messages[0].role == "assistant":  # the fixed greeting needs no replay
            messages.pop(0)
        return [UserText(m.content) if m.role == "user" else ModelTurn(text=m.content) for m in messages]

    @staticmethod
    def _msg(key: str, language: str) -> str:
        options = MESSAGES[key]
        return options.get(language, options["en"])


def build_default_advisor(settings: Settings) -> Advisor:
    from app.llm import GeminiLlm  # imported lazily so tests never build the SDK client
    return Advisor(GeminiLlm(settings), BackendClient(settings), settings)
