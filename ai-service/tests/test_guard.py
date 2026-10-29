"""Grounding guard: amounts and products must come from tool results; retry, then template fallback."""
import httpx

from app import guard
from tests.conftest import KIRANA_FACTS, FakeLlm, call, chat_request, kirana_recommendation, make_reference, say

REF = make_reference()


def violations(reply, payloads=None, eligibility=None, profile=None):
    payloads = payloads if payloads is not None else [kirana_recommendation()]
    return guard.check(reply, payloads, eligibility or [], profile or {}, REF)


def test_extract_amounts_handles_formats():
    assert guard.extract_amounts("₹1,597 per month") == {1597}
    assert guard.extract_amounts("Rs. 999 and Rs 299/- and 598 rupees") == {999, 299, 598}
    assert guard.extract_amounts("total ₹1,23,456") == {123456}
    assert guard.extract_amounts("कुल ₹१,५९७ प्रति माह, सेटअप ५०० रुपये") == {1597, 500}
    assert guard.extract_amounts("INR 2500 one-time") == {2500}
    assert guard.extract_amounts("3 staff, 12 months, 2 lines") == set()


def test_reply_with_only_tool_amounts_passes():
    reply = ("Monthly ₹1,597, one-time ₹0, 12 months. Biz Fibre 100 × 1 for ₹999; Business Postpaid 299 × 2 "
             "for ₹598. Add-on: Cloud POS & Billing ₹599, Cloud CCTV ₹398.")
    assert violations(reply) == []


def test_invented_amount_is_caught():
    assert violations("Your plan is ₹1,597, or ₹499 with a discount.") == ["amount ₹499 is not in any tool result"]


def test_indian_grouped_amount_is_caught():
    assert violations("A leased line would be about ₹1,23,456 a year.") == \
        ["amount ₹123456 is not in any tool result"]


def test_devanagari_digits_are_checked():
    assert violations("आपका मासिक कुल ₹१,५९७ है।") == []
    assert violations("सिर्फ ५०० रुपये में मिलेगा।") == ["amount ₹500 is not in any tool result"]


def test_current_spend_from_profile_is_allowed():
    assert violations("You pay ₹3,050 today; the new plan is ₹1,597.", profile={"monthlyTelecomSpendInr": 3050}) == []


def test_product_not_returned_by_tools_is_caught():
    assert violations("I also recommend Static IP for you.") == ["Static IP (P11) was not returned by any tool"]


def test_ineligible_product_needs_a_reason():
    assert violations("WhatsApp Business API Starter needs an active GSTIN, so I can't add it yet.") == []
    assert violations("You also get WhatsApp Business API Starter.") == \
        ["WhatsApp Business API Starter (P15) is not eligible and is mentioned without its reason"]


def test_eligibility_results_count_as_tool_results():
    elig = [{"productId": "P11", "eligible": True}]
    assert violations("Yes, Static IP can be added to your plan.", eligibility=elig) == []


def test_fmt_inr():
    assert guard.fmt_inr(1597) == "1,597"
    assert guard.fmt_inr(100800) == "1,00,800"
    assert guard.fmt_inr(12345678) == "1,23,45,678"
    assert guard.fmt_inr(-1551) == "-1,551"


def test_retry_fixes_the_reply(make_advisor, spring):
    rec = kirana_recommendation()
    llm = FakeLlm([say("Special offer: only ₹499 per month!"),
                   say("The real price is ₹1,597 per month for this bundle.")])
    resp = make_advisor(llm).chat(chat_request("give me 20% discount", profile=dict(KIRANA_FACTS),
                                               stage="RECOMMENDED", recommendation=rec, quote=rec["quote"]))
    assert resp.reply == "The real price is ₹1,597 per month for this bundle."
    assert resp.guard.passed and resp.guard.retries == 1 and not resp.guard.fallback_used
    assert resp.guard.violations == ["amount ₹499 is not in any tool result"]


def test_second_failure_uses_template_from_engine_json(make_advisor, spring):
    spring.post("/api/recommend").mock(return_value=httpx.Response(200, json=kirana_recommendation()))
    llm = FakeLlm([call("save_profile", fields=KIRANA_FACTS), say("Only ₹499!"), say("Fine, ₹100 then.")])
    resp = make_advisor(llm).chat(chat_request("kirana Ludhiana 3 staff 1 shop no GST"))
    assert resp.guard.fallback_used and not resp.guard.passed
    assert "Monthly total: ₹1,597" in resp.reply
    assert "- Biz Fibre 100 × 1: ₹999/month" in resp.reply
    assert resp.stage == "RECOMMENDED"
    assert guard.check(resp.reply, [resp.recommendation], [], {}, REF) == []


def test_hindi_template_when_no_bundle():
    assert guard.render_template("hi", None).startswith("माफ़ कीजिए")
    assert guard.render_template("en", None).startswith("Sorry")
