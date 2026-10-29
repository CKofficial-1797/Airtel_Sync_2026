"""Each tool calls the right Spring endpoint with the right JSON; save_profile merges and validates."""
import httpx

from app.tools import ToolContext, merge_profile
from tests.conftest import (KIRANA_FACTS, TOKEN, json_body, kirana_recommendation, make_reference,
                            quote_payload)


def ctx(backend, profile=None, recommendation=None, quote=None):
    return ToolContext(backend, make_reference(), profile or {}, recommendation, quote)


def test_save_profile_merges_validates_and_maps_city_alias(backend):
    c = ctx(backend, {"needs": ["CCTV"]})
    result = c.dispatch("save_profile", {"fields": {
        "businessType": " Kirana store ", "city": "bangalore", "employees": "3", "sites": 1.0,
        "needs": ["whatsapp", "TELEPORT"], "cityTier": 5, "favouriteColour": "blue", "gstinDeclared": "no"}})

    assert c.profile["businessType"] == "Kirana store"
    assert c.profile["city"] == "Bengaluru"
    assert c.profile["employees"] == 3 and c.profile["sites"] == 1
    assert c.profile["needs"] == ["WHATSAPP", "CCTV"]
    assert c.profile["gstinDeclared"] is False
    assert "cityTier" not in c.profile
    assert result["ignored"] == ["favouriteColour"]
    assert any("cityTier" in e for e in result["errors"])
    assert any("TELEPORT" in e for e in result["errors"])
    assert result["missingRequired"] == []
    assert result["gstinAnswered"] is True


def test_save_profile_null_clears_and_unknown_city_needs_tier(backend):
    profile = {"gstin": "32TTNXB7384T1ZW"}
    saved, _, _ = merge_profile(profile, {"gstin": None, "city": "Shillong"}, make_reference())
    assert "gstin" not in profile and set(saved) == {"gstin", "city"}
    c = ctx(backend, {"businessType": "Bakery", "city": "Shillong", "employees": 5, "sites": 1})
    result = c.dispatch("save_profile", {"fields": {}})
    assert result["missingRequired"][0].startswith("cityTier")


def test_lookup_gstin_calls_mock_registry_with_token(backend, spring):
    route = spring.get("/api/mock/gstin/32TTNXB7384T1ZW").mock(return_value=httpx.Response(200, json={
        "gstin": "32TTNXB7384T1ZW", "legalName": "Balaji Dental Care LLP", "tradeName": "Balaji Dental Care LLP",
        "constitution": "LLP", "principalPlaceOfBusiness": "Kochi", "state": "Kerala", "stateCode": "32",
        "registrationDate": "2020-06-04", "status": "Active", "active": True}))
    c = ctx(backend)
    result = c.dispatch("lookup_gstin", {"gstin": "32ttnxb 7384t1zw"})

    assert route.called
    assert route.calls.last.request.headers["X-Internal-Token"] == TOKEN
    assert result["found"] and result["tradeName"] == "Balaji Dental Care LLP" and result["active"]
    assert c.profile["gstin"] == "32TTNXB7384T1ZW"
    assert c.trace[0]["args"]["gstin"] == "32TTN*****1ZW"  # masked in the trace


def test_lookup_gstin_not_found_is_reported_not_saved(backend, spring):
    spring.get("/api/mock/gstin/27AAAXA0000A1ZR").mock(return_value=httpx.Response(404, json={
        "status": 404, "error": "Not Found", "code": "NOT_FOUND", "message": "GSTIN not found in the registry"}))
    c = ctx(backend)
    result = c.dispatch("lookup_gstin", {"gstin": "27AAAXA0000A1ZR"})
    assert result["found"] is False and result["code"] == "NOT_FOUND"
    assert "gstin" not in c.profile


def test_recommend_posts_profile_and_keeps_full_json(backend, spring):
    route = spring.post("/api/recommend").mock(return_value=httpx.Response(200, json=kirana_recommendation()))
    c = ctx(backend, dict(KIRANA_FACTS, needs=[]))
    result = c.dispatch("recommend_bundle", {})

    assert json_body(route.calls.last.request) == {"profile": {
        "businessType": "Kirana store", "city": "Ludhiana", "employees": 3, "sites": 1, "gstinDeclared": False}}
    assert result["ok"] and [i["productId"] for i in result["included"]] == ["P01", "P07"]
    assert result["quote"]["monthlyTotalInr"] == 1597
    assert c.recommendation["included"][0]["productId"] == "P01"
    assert c.quote["monthlyTotalInr"] == 1597
    assert c.last_bundle_event == "recommend"


def test_save_profile_auto_looks_up_gstin_and_recommends_when_complete(backend, spring):
    lookup = spring.get("/api/mock/gstin/32TTNXB7384T1ZW").mock(return_value=httpx.Response(200, json={
        "gstin": "32TTNXB7384T1ZW", "legalName": "Balaji Dental Care LLP", "tradeName": "Balaji Dental Care LLP",
        "constitution": "LLP", "principalPlaceOfBusiness": "Kochi", "state": "Kerala", "stateCode": "32",
        "registrationDate": "2020-06-04", "status": "Active", "active": True}))
    rec = spring.post("/api/recommend").mock(return_value=httpx.Response(200, json=kirana_recommendation()))
    c = ctx(backend)

    partial = c.dispatch("save_profile", {"fields": {"businessType": "Dental clinic", "city": "Kochi"}})
    assert "recommendation" not in partial and not rec.called

    full = c.dispatch("save_profile", {"fields": {"employees": 14, "sites": 1, "gstin": "32TTNXB7384T1ZW"}})
    assert full["gstinLookup"]["tradeName"] == "Balaji Dental Care LLP"
    assert full["recommendation"]["ok"] and rec.call_count == 1 and lookup.call_count == 1
    assert [t["tool"] for t in c.trace] == ["save_profile", "save_profile", "lookup_gstin", "recommend_bundle"]

    same = c.dispatch("save_profile", {"fields": {"employees": 14}})  # nothing changed: no new engine call
    assert "recommendation" not in same and rec.call_count == 1
    changed = c.dispatch("save_profile", {"fields": {"needs": ["BACKUP"]}})  # a fact changed: re-recommend
    assert changed["recommendation"]["ok"] and rec.call_count == 2


def test_existing_recommendation_is_not_refetched_for_the_same_profile(backend, spring):
    rec_route = spring.post("/api/recommend")
    rec = kirana_recommendation()
    c = ctx(backend, dict(KIRANA_FACTS), rec, rec["quote"])
    result = c.dispatch("save_profile", {"fields": {"city": "Ludhiana"}})
    assert "recommendation" not in result and not rec_route.called


def test_recommend_with_missing_facts_does_not_call_spring(backend, spring):
    route = spring.post("/api/recommend")
    result = ctx(backend, {"businessType": "Salon"}).dispatch("recommend_bundle", {})
    assert not route.called
    assert result["error"] == "MISSING_FACTS" and set(result["missing"]) == {"city", "employees", "sites"}


def test_get_quote_applies_changes_to_current_bundle(backend, spring):
    route = spring.post("/api/quote").mock(return_value=httpx.Response(
        200, json=quote_payload([("P01", 1, 999), ("P07", 1, 299), ("P19", 1, 599)])))
    rec = kirana_recommendation()
    c = ctx(backend, dict(KIRANA_FACTS), rec, rec["quote"])
    result = c.dispatch("get_quote", {"add": [{"productId": "Cloud POS & Billing"}],
                                      "setQuantity": [{"productId": "P07", "quantity": 1}],
                                      "remove": ["P23"]})

    body = json_body(route.calls.last.request)
    assert body["items"] == [{"productId": "P01", "quantity": 1}, {"productId": "P07", "quantity": 1},
                             {"productId": "P19", "quantity": 1}]
    assert body["removedProductIds"] == ["P23"]
    assert body["profile"]["businessType"] == "Kirana store"
    assert result["monthlyTotalInr"] == 1897
    assert c.last_bundle_event == "quote"


def test_get_quote_unknown_product_is_rejected_locally(backend, spring):
    route = spring.post("/api/quote")
    rec = kirana_recommendation()
    result = ctx(backend, dict(KIRANA_FACTS), rec, rec["quote"]).dispatch(
        "get_quote", {"add": [{"productId": "Teleporter"}]})
    assert not route.called and result["error"] == "UNKNOWN_PRODUCT"


def test_check_eligibility_sends_current_bundle_and_filters(backend, spring):
    results = [{"productId": pid, "productName": n, "eligible": pid != "P03", "conditional": False,
                "condition": None,
                "reasons": [] if pid != "P03" else [{"code": "TIER_NOT_AVAILABLE",
                                                     "message": "Biz Fibre 1G is not available in tier-2 cities"}]}
               for pid, (n, _, _) in {"P01": ("Biz Fibre 100", 0, 0), "P03": ("Biz Fibre 1G", 0, 0)}.items()]
    route = spring.post("/api/eligibility").mock(return_value=httpx.Response(
        200, json={"resolvedProfile": {}, "bundleProductIds": ["P01", "P07"], "results": results}))
    rec = kirana_recommendation()
    c = ctx(backend, dict(KIRANA_FACTS), rec, rec["quote"])
    result = c.dispatch("check_eligibility", {"productIds": ["Biz Fibre 1G"]})

    assert json_body(route.calls.last.request)["bundleProductIds"] == ["P01", "P07"]
    assert result["results"] == [{"productId": "P03", "productName": "Biz Fibre 1G", "eligible": False,
                                  "conditional": False,
                                  "reasons": ["Biz Fibre 1G is not available in tier-2 cities"]}]


def test_engine_validation_error_is_passed_to_the_model(backend, spring):
    spring.post("/api/recommend").mock(return_value=httpx.Response(400, json={
        "status": 400, "error": "Bad Request", "code": "CITY_TIER_REQUIRED",
        "message": "Unknown city 'X'. Please provide cityTier (1 or 2).", "path": "/api/recommend"}))
    c = ctx(backend, dict(KIRANA_FACTS))
    result = c.dispatch("recommend_bundle", {})
    assert result == {"ok": False, "error": "CITY_TIER_REQUIRED",
                      "message": "Unknown city 'X'. Please provide cityTier (1 or 2).", "errors": None}
    assert not c.backend_failed
