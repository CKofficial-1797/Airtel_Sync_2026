"""Tool declarations (what Gemini may call) and the dispatcher that executes them.

Only save_profile and mark_off_topic are local. Everything about products, eligibility, quantities and
prices comes from the Spring rules engine. The model receives compact summaries; the full engine JSON is
kept in ToolContext for the /chat response and for the grounding guard.
"""
from __future__ import annotations

import json
import time
from typing import Any

from app.backend_client import BackendClient, BackendResponse, BackendUnavailable
from app.reference import Reference

NEEDS = ["WHATSAPP", "CCTV", "POS_BILLING", "CLOUD_EMAIL", "BACKUP", "SECURITY", "TELEPHONY", "TOLL_FREE",
         "SMS", "WEBSITE", "FLEET", "MACHINE_IOT", "FAILOVER", "GUEST_WIFI", "STATIC_IP", "MULTI_SITE_MGMT"]

# field -> (type, min, max)
INT_FIELDS = {"cityTier": (1, 2), "employees": (1, 100000), "sites": (1, 500),
              "monthlyTelecomSpendInr": (0, 10_000_000), "officeUsers": (1, 500), "mobileLines": (1, 500),
              "cameras": (1, 500), "vehicles": (1, 500), "machines": (1, 500)}
STR_FIELDS = {"businessType", "city", "gstin", "currentInternet"}
BOOL_FIELDS = {"gstinDeclared", "fibreUnavailable"}
PROFILE_FIELDS = INT_FIELDS.keys() | STR_FIELDS | BOOL_FIELDS | {"needs"}

_ITEM = {"type": "object", "properties": {"productId": {"type": "string"}, "quantity": {"type": "integer"}},
         "required": ["productId"]}

TOOL_DECLARATIONS: list[dict[str, Any]] = [
    {
        "name": "save_profile",
        "description": "Save facts the customer stated about their business. Pass ALL facts from the message "
                       "in one call. Use null to clear a field the customer corrected. A new GSTIN is looked up "
                       "automatically (gstinLookup), and once all required facts are known the result includes "
                       "the engine's recommendation.",
        "parameters": {"type": "object", "required": ["fields"], "properties": {"fields": {
            "type": "object",
            "properties": {
                "businessType": {"type": "string", "description": "e.g. Kirana store, Dental clinic, CA firm"},
                "city": {"type": "string", "description": "City name as in the known list"},
                "cityTier": {"type": "integer", "description": "1 = big metro, 2 = smaller city; only if the city is unknown"},
                "employees": {"type": "integer"},
                "sites": {"type": "integer", "description": "Number of shops/offices/outlets/branches"},
                "gstin": {"type": "string"},
                "gstinDeclared": {"type": "boolean", "description": "true = has GST but no number given; false = no GST"},
                "currentInternet": {"type": "string"},
                "monthlyTelecomSpendInr": {"type": "integer"},
                "fibreUnavailable": {"type": "boolean"},
                "needs": {"type": "array", "items": {"type": "string", "enum": NEEDS}},
                "officeUsers": {"type": "integer"},
                "mobileLines": {"type": "integer"},
                "cameras": {"type": "integer"},
                "vehicles": {"type": "integer"},
                "machines": {"type": "integer"},
            },
        }}},
    },
    {
        "name": "lookup_gstin",
        "description": "Look up a GSTIN in the GST registry: legal/trade name, state, status (Active/Cancelled/"
                       "Suspended). Call whenever the customer gives a GSTIN number.",
        "parameters": {"type": "object", "required": ["gstin"], "properties": {"gstin": {"type": "string"}}},
    },
    {
        "name": "recommend_bundle",
        "description": "Get the recommended bundle and quote from the Accel rules engine for the saved profile. "
                       "Usually not needed: save_profile returns the recommendation automatically. Use only to "
                       "show the recommendation again.",
        "parameters": {"type": "object", "properties": {}},
    },
    {
        "name": "get_quote",
        "description": "Re-price the current bundle after the customer asks for changes. Use add/remove/"
                       "setQuantity relative to the current bundle (or items for a complete new list).",
        "parameters": {"type": "object", "properties": {
            "add": {"type": "array", "items": _ITEM, "description": "Products to add (quantity optional)"},
            "remove": {"type": "array", "items": {"type": "string"}, "description": "Product ids to remove"},
            "setQuantity": {"type": "array", "items": _ITEM, "description": "New quantity for existing items"},
            "items": {"type": "array", "items": _ITEM, "description": "Complete replacement list (rarely needed)"},
        }},
    },
    {
        "name": "check_eligibility",
        "description": "Explain whether specific products can be offered to this customer and why not. Use "
                       "for 'why can't I get X?' or when they ask for a product that was not offered.",
        "parameters": {"type": "object", "required": ["productIds"],
                       "properties": {"productIds": {"type": "array", "items": {"type": "string"}}}},
    },
    {
        "name": "mark_off_topic",
        "description": "Call when the customer's message is unrelated to their business connectivity needs.",
        "parameters": {"type": "object", "properties": {}},
    },
]


def mask_gstin(value: str | None) -> str | None:
    if not value:
        return value
    return value[:5] + "*****" + value[-3:] if len(value) >= 9 else "*****"


def normalize_gstin(value: str) -> str:
    return "".join(value.split()).replace("-", "").upper()


def missing_required(profile: dict[str, Any], ref: Reference) -> list[str]:
    missing = []
    if not profile.get("businessType"):
        missing.append("businessType")
    city = profile.get("city")
    if not city and not profile.get("cityTier"):
        missing.append("city")
    elif city and ref.cities and not ref.canonical_city(city) and not profile.get("cityTier"):
        missing.append("cityTier (city not in the known list: ask big metro = 1 or smaller city = 2)")
    for f in ("employees", "sites"):
        if not profile.get(f):
            missing.append(f)
    return missing


def gstin_answered(profile: dict[str, Any]) -> bool:
    return bool(profile.get("gstin")) or profile.get("gstinDeclared") is not None


def engine_profile(profile: dict[str, Any]) -> dict[str, Any]:
    """Only the BusinessProfile fields Spring knows, without nulls."""
    return {k: v for k, v in profile.items() if k in PROFILE_FIELDS and v is not None and v != []}


def merge_profile(profile: dict[str, Any], fields: dict[str, Any], ref: Reference
                  ) -> tuple[list[str], list[str], list[str]]:
    """Validates and merges fields into profile in place. Returns (saved, ignored, errors)."""
    saved, ignored, errors = [], [], []
    for key, value in (fields or {}).items():
        if key not in PROFILE_FIELDS:
            ignored.append(key)
            continue
        if value is None or value == "":
            profile.pop(key, None)
            saved.append(key)
            continue
        try:
            if key in INT_FIELDS:
                lo, hi = INT_FIELDS[key]
                n = int(float(value)) if not isinstance(value, bool) else None
                if n is None or n != float(value) or not lo <= n <= hi:
                    raise ValueError(f"{key} must be a whole number between {lo} and {hi}")
                profile[key] = n
            elif key in BOOL_FIELDS:
                if isinstance(value, bool):
                    profile[key] = value
                elif str(value).strip().lower() in ("true", "yes", "1"):
                    profile[key] = True
                elif str(value).strip().lower() in ("false", "no", "0"):
                    profile[key] = False
                else:
                    raise ValueError(f"{key} must be true or false")
            elif key == "needs":
                values = value if isinstance(value, list) else [value]
                bad = [v for v in values if str(v).upper() not in NEEDS]
                if bad:
                    errors.append(f"unknown needs ignored: {bad}")
                merged = set(profile.get("needs") or []) | {str(v).upper() for v in values if str(v).upper() in NEEDS}
                profile["needs"] = sorted(merged, key=NEEDS.index)
            elif key == "gstin":
                profile["gstin"] = normalize_gstin(str(value))
            elif key == "city":
                name = str(value).strip()[:100]
                profile["city"] = ref.canonical_city(name) or name
            else:
                profile[key] = str(value).strip()[:100]
            saved.append(key)
        except (TypeError, ValueError) as e:
            errors.append(str(e) if "must" in str(e) else f"{key} has an invalid value")
    if profile.get("city") and ref.canonical_city(profile["city"]):
        profile.pop("cityTier", None)  # the engine derives the tier from a known city
    return saved, ignored, errors


class ToolContext:
    """Per-turn state: the profile being built and every engine result seen in this conversation."""

    def __init__(self, backend: BackendClient, ref: Reference, profile: dict[str, Any],
                 recommendation: dict[str, Any] | None, quote: dict[str, Any] | None):
        self.backend = backend
        self.ref = ref
        self.profile = dict(profile or {})
        self.recommendation = recommendation
        self.quote = quote
        self.eligibility_results: list[dict[str, Any]] = []
        self.gstin_results: list[dict[str, Any]] = []
        self.payloads: list[Any] = [p for p in (recommendation, quote) if p]  # everything the guard may cite
        self.trace: list[dict[str, Any]] = []
        self.last_bundle_event: str | None = None  # "recommend" | "quote"
        self.off_topic = False
        self.backend_failed = False
        # Profile the current recommendation was built for: a change triggers a fresh recommendation.
        self._recommended_for = self._fingerprint() if recommendation else None

    def _fingerprint(self) -> str:
        return json.dumps(engine_profile(self.profile), sort_keys=True)

    # ---------------------------------------------------------------- dispatch
    def dispatch(self, name: str, args: dict[str, Any]) -> dict[str, Any]:
        start = time.perf_counter()
        entry = {"tool": name, "args": self._masked(args), "ok": False, "ms": 0}
        self.trace.append(entry)  # appended first so automatic follow-up calls appear after their trigger
        handler = getattr(self, f"_tool_{name}", None)
        try:
            result = handler(args or {}) if handler else {"ok": False, "error": f"Unknown tool {name}"}
        except BackendUnavailable:
            self.backend_failed = True
            result = {"ok": False, "error": "BACKEND_UNAVAILABLE",
                      "message": "The pricing system is not reachable. Do not guess; apologise and ask to retry."}
        entry.update(ok=bool(result.get("ok")), ms=int((time.perf_counter() - start) * 1000))
        return result

    @staticmethod
    def _masked(args: dict[str, Any]) -> dict[str, Any]:
        def mask(obj: Any) -> Any:
            if isinstance(obj, dict):
                return {k: (mask_gstin(normalize_gstin(v)) if k == "gstin" and isinstance(v, str) else mask(v))
                        for k, v in obj.items()}
            if isinstance(obj, list):
                return [mask(v) for v in obj]
            return obj
        return mask(dict(args or {}))

    # ---------------------------------------------------------------- tools
    def _tool_save_profile(self, args: dict[str, Any]) -> dict[str, Any]:
        """Saves facts, then does the deterministic follow-ups in the same step (saves model calls):
        looks up a newly given GSTIN, and fetches the recommendation once all required facts are known
        (or again when they changed after a recommendation)."""
        saved, ignored, errors = merge_profile(self.profile, args.get("fields") or {}, self.ref)
        result: dict[str, Any] = {"ok": not errors, "saved": saved, "ignored": ignored, "errors": errors}
        gstin = self.profile.get("gstin")
        if "gstin" in saved and gstin:
            result["gstinLookup"] = self.dispatch("lookup_gstin", {"gstin": gstin, "auto": True})
        missing = missing_required(self.profile, self.ref)
        result.update(profile=self.profile, missingRequired=missing, gstinAnswered=gstin_answered(self.profile))
        if not missing and gstin_answered(self.profile) and self._fingerprint() != self._recommended_for \
                and not self.backend_failed:
            result["recommendation"] = self.dispatch("recommend_bundle", {"auto": True})
        return result

    def _tool_mark_off_topic(self, args: dict[str, Any]) -> dict[str, Any]:
        self.off_topic = True
        return {"ok": True}

    def _tool_lookup_gstin(self, args: dict[str, Any]) -> dict[str, Any]:
        gstin = normalize_gstin(str(args.get("gstin") or ""))
        if not gstin:
            return {"ok": False, "error": "No GSTIN given"}
        r = self.backend.gstin(gstin)
        if r.ok:
            b = r.body
            self.profile["gstin"] = b["gstin"]
            self.profile.pop("gstinDeclared", None)
            result = {"ok": True, "found": True, "legalName": b["legalName"], "tradeName": b["tradeName"],
                      "state": b["state"], "status": b["status"], "active": b["active"],
                      "principalPlaceOfBusiness": b["principalPlaceOfBusiness"]}
            if not b["active"]:
                result["note"] = ("GSTIN is not active: GST-only products cannot be offered. "
                                  "Continue with the eligible bundle.")
        else:
            body = r.body or {}
            result = {"ok": True, "found": False, "code": body.get("code", f"HTTP_{r.status}"),
                      "errors": body.get("errors") or [body.get("message", "GSTIN could not be verified")],
                      "note": "Ask the customer to re-check the number; until then treat it as no verified GSTIN."}
        self.gstin_results.append(result)
        return result

    def _tool_recommend_bundle(self, args: dict[str, Any]) -> dict[str, Any]:
        missing = missing_required(self.profile, self.ref)
        if missing:
            return {"ok": False, "error": "MISSING_FACTS", "missing": missing}
        r = self.backend.recommend(engine_profile(self.profile))
        if not r.ok:
            return self._engine_error(r)
        self.recommendation = r.body
        self.quote = r.body.get("quote")
        self.payloads.append(r.body)
        self.last_bundle_event = "recommend"
        self._recommended_for = self._fingerprint()
        return summarize_recommendation(r.body)

    def _tool_get_quote(self, args: dict[str, Any]) -> dict[str, Any]:
        missing = missing_required(self.profile, self.ref)
        if missing:
            return {"ok": False, "error": "MISSING_FACTS", "missing": missing}
        items, removed, problems = self._apply_changes(args)
        if problems:
            return {"ok": False, "error": "UNKNOWN_PRODUCT", "message": "; ".join(problems)}
        if not items:
            return {"ok": False, "error": "EMPTY_BUNDLE", "message": "The bundle would be empty."}
        r = self.backend.quote(engine_profile(self.profile), items, removed)
        if not r.ok:
            return self._engine_error(r)
        self.quote = r.body
        self.payloads.append(r.body)
        self.last_bundle_event = "quote"
        return summarize_quote(r.body)

    def _tool_check_eligibility(self, args: dict[str, Any]) -> dict[str, Any]:
        missing = missing_required(self.profile, self.ref)
        if missing:
            return {"ok": False, "error": "MISSING_FACTS", "missing": missing}
        wanted = [self.ref.product_id(str(p)) for p in (args.get("productIds") or [])]
        r = self.backend.eligibility(engine_profile(self.profile), list(self.current_items()))
        if not r.ok:
            return self._engine_error(r)
        results = r.body["results"]
        self.eligibility_results.extend(results)
        selected = [x for x in results if x["productId"] in wanted] if any(wanted) else \
            [x for x in results if not x["eligible"]]
        return {"ok": True, "results": [{"productId": x["productId"], "productName": x["productName"],
                                         "eligible": x["eligible"], "conditional": x["conditional"],
                                         "reasons": [m["message"] for m in x["reasons"]]} for x in selected]}

    # ---------------------------------------------------------------- helpers
    def current_items(self) -> dict[str, int]:
        if self.quote and self.quote.get("lines") is not None:
            return {l["productId"]: l["quantity"] for l in self.quote["lines"]}
        if self.recommendation:
            return {l["productId"]: l["quantity"] for l in self.recommendation["included"]}
        return {}

    def _default_quantity(self, pid: str) -> int:
        for addon in (self.quote or {}).get("availableAddOns", []):
            if addon["productId"] == pid:
                return addon["defaultQuantity"]
        for line in (self.recommendation or {}).get("optionalAddOns", []):
            if line["productId"] == pid:
                return line["quantity"]
        return 1

    def _apply_changes(self, args: dict[str, Any]) -> tuple[list[dict[str, Any]], list[str], list[str]]:
        problems: list[str] = []

        def pid_of(ref: Any) -> str | None:
            pid = self.ref.product_id(str(ref)) if ref is not None else None
            if not pid:
                problems.append(f"unknown product '{ref}'")
            return pid

        if args.get("items"):
            items = {}
            for it in args["items"]:
                pid = pid_of(it.get("productId"))
                if pid:
                    items[pid] = int(it.get("quantity") or self._default_quantity(pid))
        else:
            items = self.current_items()
        removed = []
        for ref in args.get("remove") or []:
            pid = pid_of(ref)
            if pid:
                items.pop(pid, None)
                removed.append(pid)
        for it in args.get("setQuantity") or []:
            pid = pid_of(it.get("productId"))
            if pid and it.get("quantity"):
                items[pid] = int(it["quantity"])
        for it in args.get("add") or []:
            pid = pid_of(it.get("productId"))
            if pid:
                items[pid] = int(it.get("quantity") or self._default_quantity(pid))
                if pid in removed:
                    removed.remove(pid)
        clean = [{"productId": k, "quantity": max(1, min(500, v))} for k, v in items.items()]
        return clean, removed, problems

    @staticmethod
    def _engine_error(r: BackendResponse) -> dict[str, Any]:
        body = r.body or {}
        return {"ok": False, "error": body.get("code") or f"HTTP_{r.status}",
                "message": body.get("message"), "errors": body.get("errors")}


# -------------------------------------------------------------------- summaries for the model
def _totals(q: dict[str, Any]) -> dict[str, Any]:
    return {"monthlyTotalInr": q["monthlyTotalInr"], "oneTimeTotalInr": q["oneTimeTotalInr"],
            "contractMonths": q["contractMonths"], "estimatedActivationDate": q["estimatedActivationDate"],
            "conditional": q.get("conditional", False), "comparison": q.get("comparison"), "notes": q["notes"]}


def summarize_recommendation(rec: dict[str, Any]) -> dict[str, Any]:
    rp = rec["resolvedProfile"]
    autofill = rp.get("gstinAutofill") or {}
    return {
        "ok": True,
        "segment": rec["segment"],
        "city": rp.get("city"), "cityTier": rp["cityTier"],
        "gstinStatus": rp["gstinStatus"], "gstinRegistryStatus": rp.get("gstinRegistryStatus"),
        "businessName": autofill.get("tradeName"),
        "warnings": rp.get("warnings", []),
        "included": [{"productId": l["productId"], "productName": l["productName"], "quantity": l["quantity"],
                      "unitPriceInr": l["unitPriceInr"], "monthlyTotalInr": l["monthlyTotalInr"],
                      "why": l["why"], "conditional": l["conditional"]} for l in rec["included"]],
        "optionalAddOns": [{"productId": l["productId"], "productName": l["productName"], "quantity": l["quantity"],
                            "monthlyTotalInr": l["monthlyTotalInr"], "replaces": l.get("replaces"),
                            "conditional": l["conditional"]} for l in rec["optionalAddOns"]],
        "excluded": [{"productId": e["productId"], "productName": e["productName"],
                      "reasons": [r["message"] for r in e["reasons"]]} for e in rec["excluded"]],
        "notes": rec.get("notes", []),
        "quote": _totals(rec["quote"]),
    }


def summarize_quote(q: dict[str, Any]) -> dict[str, Any]:
    return {
        "ok": True,
        "lines": [{"productId": l["productId"], "productName": l["productName"], "quantity": l["quantity"],
                   "unitPriceInr": l["unitPriceInr"], "monthlyTotalInr": l["monthlyTotalInr"],
                   "setupFeeInr": l["setupFeeInr"], "conditional": l["conditional"]} for l in q["lines"]],
        **_totals(q),
        "removed": [{"productId": r["productId"], "productName": r["productName"],
                     "reasons": [m["message"] for m in r["reasons"]]} for r in q["removed"]],
        "availableAddOns": [{"productId": a["productId"], "productName": a["productName"],
                             "unitPriceInr": a["unitPriceInr"], "defaultQuantity": a["defaultQuantity"]}
                            for a in q["availableAddOns"]],
    }
