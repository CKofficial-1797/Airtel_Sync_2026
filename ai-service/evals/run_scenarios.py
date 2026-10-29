"""Live evaluation of the advisor against real Gemini + the running Spring backend. NOT part of pytest.

Usage (from ai-service/, with Spring on :8080 and `uvicorn app.main:app --port 8000` running):
    .venv/Scripts/python evals/run_scenarios.py            # all 15 (Windows)
    .venv/bin/python evals/run_scenarios.py --only 1,5,11  # a subset (macOS/Linux)

For each scenario the runner sends the user turns to ai-service /chat (keeping history/state like Spring
does), then independently asks Spring /api/recommend (or /api/quote) for the final profile and checks:
  (a) the bot's bundle == the engine's bundle (product ids + quantities + monthly total)
  (b) the grounding guard passed on every turn (no ungrounded amounts/products)
  (c) the reply language/script matches the user's
  (d) the scenario-specific expectations in scenarios.json
Writes a JSON report to evals/results/. Uses roughly 2-4 Gemini calls per turn.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent))

from app.advisor import detect_language  # noqa: E402
from app.config import get_settings  # noqa: E402
from app.tools import engine_profile  # noqa: E402

STAGE_ORDER = ["COLLECTING", "OUT_OF_SCOPE", "RECOMMENDED", "CUSTOMISING", "READY_FOR_CHECKOUT"]


def devanagari_ratio(text: str) -> float:
    letters = [c for c in text if c.isalpha()]
    return sum(0x0900 <= ord(c) <= 0x097F for c in letters) / len(letters) if letters else 0.0


def language_ok(user_text: str, reply: str) -> bool:
    lang = detect_language(user_text)
    ratio = devanagari_ratio(reply)
    return ratio > 0.5 if lang == "hi" else ratio < 0.05


def bundle_of(rec: dict[str, Any] | None, quote: dict[str, Any] | None) -> dict[str, int]:
    if quote and quote.get("lines") is not None:
        return {l["productId"]: l["quantity"] for l in quote["lines"]}
    return {l["productId"]: l["quantity"] for l in (rec or {}).get("included", [])}


class Runner:
    def __init__(self, ai_url: str, backend_url: str, delay: float):
        s = get_settings()
        self.ai = httpx.Client(base_url=ai_url, timeout=120, headers={"X-Internal-Token": s.internal_token})
        self.spring = httpx.Client(base_url=backend_url, timeout=30)
        self.delay = delay
        self.gst_only = {p["productId"] for p in self.spring.get("/api/products").json() if p["requiresGstin"]}

    def chat(self, session_id: str, messages: list, profile: dict, state: dict) -> dict:
        r = self.ai.post("/chat", json={"sessionId": session_id, "messages": messages, "profile": profile,
                                        "uiLanguage": None, "state": state})
        r.raise_for_status()
        return r.json()

    def run(self, sc: dict[str, Any]) -> dict[str, Any]:
        sid = f"eval-{sc['id']}-{int(time.time())}"
        messages: list[dict[str, str]] = []
        profile: dict[str, Any] = {}
        state: dict[str, Any] = {"stage": "COLLECTING", "recommendation": None, "quote": None}
        turns_log = []

        def send(text: str) -> dict:
            nonlocal profile, state
            messages.append({"role": "user", "content": text})
            resp = self.chat(sid, messages, profile, state)
            messages.append({"role": "assistant", "content": resp["reply"]})
            profile = resp["profile"]
            state = {"stage": resp["stage"], "recommendation": resp["recommendation"], "quote": resp["quote"]}
            turns_log.append({"user": text, "reply": resp["reply"], "stage": resp["stage"],
                              "tools": [t["tool"] for t in resp["toolTrace"]], "guard": resp["guard"]})
            time.sleep(self.delay)
            return resp

        for text in sc["turns"]:
            send(text)
            if STAGE_ORDER.index(state["stage"]) >= STAGE_ORDER.index("RECOMMENDED"):
                break
        for text in sc.get("afterRecommendation", []):
            send(text)

        checks = self.check(sc, turns_log, profile, state)
        return {"id": sc["id"], "name": sc["name"], "passed": all(c["ok"] for c in checks), "checks": checks,
                "turns": turns_log, "finalProfile": profile, "finalStage": state["stage"]}

    # ------------------------------------------------------------------
    def check(self, sc: dict, turns: list, profile: dict, state: dict) -> list[dict[str, Any]]:
        exp = sc.get("expect", {})
        rec, quote = state["recommendation"], state["quote"]
        bot_bundle = bundle_of(rec, quote)
        last, first = turns[-1], turns[0]
        checks: list[dict[str, Any]] = []

        def add(name: str, ok: bool, detail: Any = None) -> None:
            checks.append({"check": name, "ok": bool(ok), "detail": detail})

        reached = any(STAGE_ORDER.index(t["stage"]) >= STAGE_ORDER.index("RECOMMENDED") for t in turns)
        add("reached RECOMMENDED", reached and rec is not None, [t["stage"] for t in turns])

        # (a) bot bundle == engine bundle for the final profile
        if rec is not None:
            p = engine_profile(profile)
            engine_rec = self.spring.post("/api/recommend", json={"profile": p}).json()
            if exp.get("compare") == "quote":
                items = {l["productId"]: l["quantity"] for l in engine_rec["included"]}
                changes = exp.get("quoteChanges", {})
                for pid in changes.get("remove", []):
                    items.pop(pid, None)
                items.update(changes.get("add", {}))
                engine_q = self.spring.post("/api/quote", json={
                    "profile": p, "items": [{"productId": k, "quantity": v} for k, v in items.items()],
                    "removedProductIds": changes.get("remove", [])}).json()
                expected = {l["productId"]: l["quantity"] for l in engine_q["lines"]}
                add("(a) bundle == engine quote", bot_bundle == expected
                    and quote["monthlyTotalInr"] == engine_q["monthlyTotalInr"],
                    {"bot": bot_bundle, "engine": expected, "botTotal": quote["monthlyTotalInr"],
                     "engineTotal": engine_q["monthlyTotalInr"]})
            else:
                expected = {l["productId"]: l["quantity"] for l in engine_rec["included"]}
                rec_bundle = {l["productId"]: l["quantity"] for l in rec["included"]}
                add("(a) recommendation == engine", rec_bundle == expected
                    and rec["quote"]["monthlyTotalInr"] == engine_rec["quote"]["monthlyTotalInr"],
                    {"bot": rec_bundle, "engine": expected})

        # (b) guard
        add("(b) guard passed every turn", all(t["guard"]["passed"] for t in turns),
            [t["guard"] for t in turns if not t["guard"]["passed"] or t["guard"]["retries"]])
        # (c) language
        add("(c) reply language matches", all(language_ok(t["user"], t["reply"]) for t in turns))

        # (d) scenario expectations
        included = {l["productId"] for l in (rec or {}).get("included", [])}
        offered = included | {l["productId"] for l in (rec or {}).get("optionalAddOns", [])} | set(bot_bundle)
        reply = last["reply"].lower()
        for pid, qty in exp.get("includedEquals", {}).items():
            add(f"{pid} x{qty} included", bot_bundle.get(pid) == qty, bot_bundle)
        if "includedEquals" in exp:
            add("no extra products", set(bot_bundle) == set(exp["includedEquals"]), bot_bundle)
        for pid in exp.get("includedContains", []):
            add(f"{pid} included", pid in included, sorted(included))
        for pid in exp.get("bundleContains", []):
            add(f"{pid} in bundle", pid in bot_bundle, bot_bundle)
        for pid in exp.get("notIncluded", []):
            add(f"{pid} not included", pid not in bot_bundle and pid not in included)
        for pid in exp.get("notOffered", []):
            add(f"{pid} never offered", pid not in offered, sorted(offered))
        if exp.get("noGstOnlyProducts"):
            add("no GST-only products", not (offered & self.gst_only), sorted(offered & self.gst_only))
        if "primary" in exp:
            primary = next((l["productId"] for l in (rec or {}).get("included", []) if l["role"] == "PRIMARY"), None)
            add(f"primary {exp['primary']}", primary == exp["primary"], primary)
        for pid in exp.get("conditional", []):
            cond = {l["productId"] for l in (rec or {}).get("included", []) if l["conditional"]}
            add(f"{pid} conditional", pid in cond, sorted(cond))
        for pid, code in exp.get("excludedReason", {}).items():
            codes = [r["code"] for e in (rec or {}).get("excluded", []) if e["productId"] == pid for r in e["reasons"]]
            add(f"{pid} excluded with {code}", code in codes, codes)
        for key, value in exp.get("profile", {}).items():
            add(f"profile.{key} == {value}", profile.get(key) == value, profile.get(key))
        if exp.get("replyContainsAny"):
            add(f"reply mentions one of {exp['replyContainsAny']}",
                any(s.lower() in reply for s in exp["replyContainsAny"]))
        for s in exp.get("replyNotContains", []):
            add(f"reply does not contain '{s}'", s.lower() not in reply)
        for tool in exp.get("lastTurnTools", []):
            add(f"{tool} called in last turn", tool in last["tools"], last["tools"])
        for tool in exp.get("lastTurnToolsAbsent", []):
            add(f"{tool} not called in last turn", tool not in last["tools"], last["tools"])
        for stage in exp.get("stageNot", []):
            add(f"stage is not {stage}", last["stage"] != stage, last["stage"])
        if "firstTurnStage" in exp:
            add(f"first turn stage {exp['firstTurnStage']}", first["stage"] == exp["firstTurnStage"], first["stage"])
        return checks


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", help="comma-separated scenario ids, e.g. 1,5,11")
    parser.add_argument("--rerun-failed", action="store_true",
                        help="run only the scenarios that failed in the latest report (saves Gemini quota)")
    parser.add_argument("--delay", type=float, default=2.0, help="seconds between /chat calls (free tier)")
    parser.add_argument("--ai-url", default="http://localhost:8000")
    parser.add_argument("--backend-url", default=get_settings().backend_base_url)
    args = parser.parse_args()

    scenarios = json.loads((ROOT / "scenarios.json").read_text(encoding="utf-8"))
    if args.only:
        wanted = {int(x) for x in args.only.split(",")}
        scenarios = [s for s in scenarios if s["id"] in wanted]
    if args.rerun_failed:
        reports = sorted((ROOT / "results").glob("run-*.json"))
        if not reports:
            print("No previous report in evals/results/")
            return 1
        latest = json.loads(reports[-1].read_text(encoding="utf-8"))
        failed = {r["id"] for r in latest["results"] if not r["passed"]}
        scenarios = [s for s in scenarios if s["id"] in failed]
        print(f"Re-running failed scenarios from {reports[-1].name}: {sorted(failed)}")

    runner = Runner(args.ai_url, args.backend_url, args.delay)
    results = []
    for sc in scenarios:
        print(f"[{sc['id']:>2}] {sc['name']} ...", flush=True)
        try:
            results.append(runner.run(sc))
        except Exception as e:  # report and continue with the next scenario
            results.append({"id": sc["id"], "name": sc["name"], "passed": False,
                            "checks": [{"check": "run", "ok": False, "detail": repr(e)}], "turns": []})

    print("\n  #  Result  Scenario")
    for r in results:
        print(f" {r['id']:>2}  {'PASS' if r['passed'] else 'FAIL':<6}  {r['name']}")
        for c in r["checks"]:
            if not c["ok"]:
                print(f"       x {c['check']}: {json.dumps(c['detail'], ensure_ascii=False)[:200]}")
    passed = sum(r["passed"] for r in results)
    print(f"\n{passed}/{len(results)} scenarios passed")

    out_dir = ROOT / "results"
    out_dir.mkdir(exist_ok=True)
    out = out_dir / f"run-{datetime.now():%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps({"passed": passed, "total": len(results), "results": results},
                              ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Report: {out}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
