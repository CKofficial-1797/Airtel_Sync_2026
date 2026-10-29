"""The tool-calling loop: one-shot facts, loop limit, Spring failures, deterministic stages."""
import httpx

from app.advisor import detect_language, is_acceptance
from tests.conftest import (KIRANA_FACTS, FakeLlm, call, chat_request, kirana_recommendation, quote_payload,
                            say)

ONE_SHOT = "I run a kirana store in Ludhiana, 3 staff, one shop, no GST"


def test_one_shot_message_saves_profile_then_recommends_in_same_turn(make_advisor, spring):
    route = spring.post("/api/recommend").mock(return_value=httpx.Response(200, json=kirana_recommendation()))
    llm = FakeLlm([
        call("save_profile", fields=KIRANA_FACTS),
        say("Here is your plan: ₹1,597/month, ₹0 one-time, 12 months. Biz Fibre 100 × 1 (₹999) and "
            "Business Postpaid 299 × 2 (₹598). Add-on: Cloud POS & Billing ₹599. "
            "Would you like to change anything, or go ahead?"),
    ])
    resp = make_advisor(llm).chat(chat_request(ONE_SHOT))

    # save_profile completed the facts, so the recommendation was fetched in the same step: 2 model calls.
    assert [t.tool for t in resp.tool_trace] == ["save_profile", "recommend_bundle"]
    assert resp.tool_trace[1].args == {"auto": True}
    assert route.call_count == 1
    assert llm.calls == 2
    assert resp.stage == "RECOMMENDED"
    assert resp.profile["city"] == "Ludhiana" and resp.profile["employees"] == 3
    assert resp.recommendation["included"][0]["productId"] == "P01"
    assert resp.quote["monthlyTotalInr"] == 1597
    assert resp.guard.passed and resp.guard.retries == 0
    assert resp.detected_language == "en"


def test_system_prompt_contains_state_and_reference_but_no_prices(make_advisor, spring):
    llm = FakeLlm([say("Which city is your shop in?")])
    make_advisor(llm).chat(chat_request("I have a kirana store", profile={"businessType": "Kirana store"}))
    system = llm.systems[0]
    assert "Ludhiana (tier 2)" in system and "P15 WhatsApp Business API Starter" in system
    assert "Missing required facts: ['city', 'employees', 'sites']" in system
    assert "₹" not in system.split("# Reference")[1]


def test_loop_stops_after_six_iterations(make_advisor, spring):
    llm = FakeLlm(repeat=call("save_profile", fields={"employees": 3}))
    resp = make_advisor(llm).chat(chat_request("3 staff"))
    assert llm.calls == 6
    assert len(resp.tool_trace) == 6
    assert resp.reply.startswith("Sorry, I need a bit more detail")
    assert resp.stage == "COLLECTING"


def test_spring_500_gives_friendly_reply_and_keeps_stage(make_advisor, spring):
    spring.post("/api/quote").mock(return_value=httpx.Response(500, json={"status": 500}))
    rec = kirana_recommendation()
    llm = FakeLlm([call("get_quote", remove=["P07"])])
    resp = make_advisor(llm).chat(chat_request("remove the mobile lines", profile=dict(KIRANA_FACTS),
                                               stage="RECOMMENDED", recommendation=rec, quote=rec["quote"]))
    assert resp.reply == "Sorry, I couldn't fetch prices right now. Please try again in a moment."
    assert resp.stage == "RECOMMENDED"
    assert resp.quote["monthlyTotalInr"] == 1597  # unchanged, nothing guessed
    assert resp.tool_trace[0].ok is False
    assert llm.calls == 1


def test_spring_timeout_in_hindi_gives_hindi_friendly_reply(make_advisor, spring):
    spring.post("/api/recommend").mock(side_effect=httpx.ReadTimeout("timeout"))
    llm = FakeLlm([call("recommend_bundle")])
    resp = make_advisor(llm).chat(chat_request("मेरी किराना दुकान है", profile=dict(KIRANA_FACTS)))
    assert resp.reply.startswith("माफ़ कीजिए")
    assert resp.stage == "COLLECTING"
    assert resp.detected_language == "hi"


def test_quote_change_moves_to_customising(make_advisor, spring):
    new_quote = quote_payload([("P01", 1, 999), ("P07", 1, 299)])
    spring.post("/api/quote").mock(return_value=httpx.Response(200, json=new_quote))
    rec = kirana_recommendation()
    llm = FakeLlm([call("get_quote", setQuantity=[{"productId": "P07", "quantity": 1}]),
                   say("Done. Your new monthly total is ₹1,298.")])
    resp = make_advisor(llm).chat(chat_request("only one mobile line please", profile=dict(KIRANA_FACTS),
                                               stage="RECOMMENDED", recommendation=rec, quote=rec["quote"]))
    assert resp.stage == "CUSTOMISING"
    assert resp.quote["monthlyTotalInr"] == 1298
    assert resp.recommendation == rec
    assert resp.guard.passed


def test_acceptance_after_recommendation_is_ready_for_checkout(make_advisor, spring):
    rec = kirana_recommendation()
    llm = FakeLlm([say("Great! Next you'll review the quote and complete a quick KYC.")])
    resp = make_advisor(llm).chat(chat_request("haan theek hai", profile=dict(KIRANA_FACTS),
                                               stage="RECOMMENDED", recommendation=rec, quote=rec["quote"]))
    assert resp.stage == "READY_FOR_CHECKOUT"


def test_acceptance_without_a_bundle_stays_collecting(make_advisor, spring):
    llm = FakeLlm([say("Sure! What type of business do you run?")])
    resp = make_advisor(llm).chat(chat_request("yes"))
    assert resp.stage == "COLLECTING"


def test_off_topic_marks_stage(make_advisor, spring):
    llm = FakeLlm([call("mark_off_topic"), say("I can help with internet and phone plans for your business.")])
    resp = make_advisor(llm).chat(chat_request("who will win the cricket match?"))
    assert resp.stage == "OUT_OF_SCOPE"


def test_language_and_acceptance_helpers():
    assert detect_language("bhai restaurant chain hai Bangalore mein") == "hinglish"
    assert detect_language("मेरी दुकान है") == "hi"
    assert detect_language("I run a salon") == "en"
    assert is_acceptance("Yes, go ahead") and is_acceptance("ठीक है") and is_acceptance("theek hai")
    assert not is_acceptance("yes but remove CCTV") and not is_acceptance("no") and not is_acceptance("why?")
