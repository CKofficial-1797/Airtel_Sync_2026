"""Deterministic grounding guard, run on every final reply.

1. Every rupee amount in the reply must appear in an engine (tool) result from this conversation.
2. Every catalogue product named in the reply must come from a tool result; a product the engine
   marked ineligible may only be named in a sentence that also gives a reason.
On a violation the advisor retries once with a corrective note, then falls back to a template rendered
from the engine JSON (see render_template).
"""
from __future__ import annotations

import re
from typing import Any, Iterable

from app.reference import Reference

_DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
_NUM = r"([0-9][0-9,]*(?:\.[0-9]+)?)"
_AMOUNT_PATTERNS = [
    re.compile(r"(?:₹|\brs\.?|\binr\b|रु\.?)\s*" + _NUM, re.IGNORECASE),
    re.compile(_NUM + r"\s*(?:/-|rupees?\b|rupaye\b|रुपये|रुपए|रुपया|\brs\b)", re.IGNORECASE),
]
_PRODUCT_ID = re.compile(r"\bP(0[1-9]|1[0-9]|2[0-5])\b")
_SENTENCE_SPLIT = re.compile(r"(?<=[.!?।])\s+|\n+")
_REASON_WORDS = re.compile(
    r"not available|unavailable|needs?|requires?|required|only|can't|cannot|can not|isn't|not eligible|"
    r"removed|because|since|gstin|tier|employees|sites|cancelled|suspended|inactive|"
    r"नहीं|ज़रूरत|जरूरत|आवश्यक|केवल|सिर्फ|हटाया|क्योंकि|nahi|zaroorat|sirf",
    re.IGNORECASE,
)


def extract_amounts(text: str) -> set[int]:
    """Rupee amounts in a reply: ₹1,234 / Rs 1234 / 1234 rupees / १२३४ रुपये / ₹1,23,456."""
    t = text.translate(_DEVANAGARI_DIGITS)
    found: set[int] = set()
    for pattern in _AMOUNT_PATTERNS:
        for m in pattern.finditer(t):
            digits = m.group(1).replace(",", "")
            try:
                found.add(int(round(float(digits))))
            except ValueError:
                continue
    return found


def allowed_amounts(payloads: Iterable[Any], profile: dict[str, Any]) -> set[int]:
    """Every *Inr number in the engine payloads (unit prices, line totals, totals, add-ons, comparison)."""
    allowed: set[int] = set()

    def walk(obj: Any) -> None:
        if isinstance(obj, dict):
            for k, v in obj.items():
                if isinstance(v, (int, float)) and not isinstance(v, bool) and "inr" in k.lower():
                    allowed.add(abs(int(round(v))))
                else:
                    walk(v)
        elif isinstance(obj, list):
            for v in obj:
                walk(v)

    for p in payloads:
        walk(p)
    spend = profile.get("monthlyTelecomSpendInr")
    if isinstance(spend, int):
        allowed.add(spend)
    return allowed


def mentioned_products(text: str, ref: Reference) -> set[str]:
    ids = {f"P{m.group(1)}" for m in _PRODUCT_ID.finditer(text)}
    low = text.lower()
    for p in ref.products.values():
        if p.name.lower() in low:
            ids.add(p.product_id)
    return ids


def _ids(items: Iterable[dict[str, Any]] | None) -> set[str]:
    return {i["productId"] for i in (items or [])}


def product_sets(payloads: Iterable[Any], eligibility: list[dict[str, Any]]) -> tuple[set[str], set[str]]:
    """(offered, ineligible) product ids across all engine results."""
    offered: set[str] = set()
    ineligible: set[str] = set()
    for p in payloads:
        if not isinstance(p, dict):
            continue
        if "included" in p:  # recommendation
            offered |= _ids(p["included"]) | _ids(p.get("optionalAddOns"))
            ineligible |= _ids(p.get("excluded"))
            p = p.get("quote") or {}
        offered |= _ids(p.get("lines")) | _ids(p.get("availableAddOns"))
        ineligible |= _ids(p.get("removed")) | _ids(p.get("ineligibleProducts"))
    for r in eligibility:
        (offered if r.get("eligible") else ineligible).add(r["productId"])
    return offered, ineligible - offered


def check(reply: str, payloads: list[Any], eligibility: list[dict[str, Any]], profile: dict[str, Any],
          ref: Reference) -> list[str]:
    violations: list[str] = []
    allowed = allowed_amounts(payloads, profile)
    for amount in sorted(extract_amounts(reply)):
        if amount not in allowed:
            violations.append(f"amount ₹{amount} is not in any tool result")

    offered, ineligible = product_sets(payloads, eligibility)
    sentences = _SENTENCE_SPLIT.split(reply)
    for pid in sorted(mentioned_products(reply, ref)):
        if pid in offered:
            continue
        name = ref.products[pid].name if pid in ref.products else pid
        if pid in ineligible:
            in_sentences = [s for s in sentences if pid in s or name.lower() in s.lower()]
            if in_sentences and all(_REASON_WORDS.search(s) for s in in_sentences):
                continue
            violations.append(f"{name} ({pid}) is not eligible and is mentioned without its reason")
        else:
            violations.append(f"{name} ({pid}) was not returned by any tool")
    return violations


# -------------------------------------------------------------------- deterministic fallback
def fmt_inr(n: int) -> str:
    """Indian digit grouping: 100800 -> 1,00,800."""
    s = str(abs(int(n)))
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        groups = []
        while len(head) > 2:
            groups.insert(0, head[-2:])
            head = head[:-2]
        if head:
            groups.insert(0, head)
        s = ",".join(groups) + "," + tail
    return ("-" if n < 0 else "") + s


_TEMPLATES = {
    "en": {
        "head": "Here is your recommended Accel Business bundle:",
        "totals": "Monthly total: ₹{m} · One-time setup: ₹{o} · Contract: {c} months",
        "line": "- {name} × {q}: ₹{t}/month",
        "removed": "Removed: {name} ({reason})",
        "ask": "Would you like to change anything, or go ahead?",
        "collect": "Sorry, I didn't get that. Could you tell me your type of business, city, number of "
                   "employees and number of shops or offices?",
    },
    "hi": {
        "head": "आपके लिए सुझाया गया Accel Business बंडल:",
        "totals": "मासिक कुल: ₹{m} · एक-बार सेटअप: ₹{o} · अनुबंध: {c} महीने",
        "line": "- {name} × {q}: ₹{t}/माह",
        "removed": "हटाया गया: {name} ({reason})",
        "ask": "क्या आप कुछ बदलना चाहेंगे, या आगे बढ़ें?",
        "collect": "माफ़ कीजिए, मैं समझ नहीं पाया। कृपया अपने व्यवसाय का प्रकार, शहर, कर्मचारियों की संख्या "
                   "और दुकानों/ऑफ़िस की संख्या बताइए।",
    },
}


def render_template(language: str, quote: dict[str, Any] | None) -> str:
    t = _TEMPLATES["hi" if language == "hi" else "en"]
    if not quote or not quote.get("lines"):
        return t["collect"]
    out = [t["head"], t["totals"].format(m=fmt_inr(quote["monthlyTotalInr"]), o=fmt_inr(quote["oneTimeTotalInr"]),
                                         c=quote["contractMonths"])]
    out += [t["line"].format(name=l["productName"], q=l["quantity"], t=fmt_inr(l["monthlyTotalInr"]))
            for l in quote["lines"]]
    out += [t["removed"].format(name=r["productName"], reason=r["reasons"][0]["message"] if r["reasons"] else "")
            for r in quote.get("removed", [])]
    out.append(t["ask"])
    return "\n".join(out)
