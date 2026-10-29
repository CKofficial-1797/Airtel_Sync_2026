"""HTTP layer: health, X-Internal-Token enforcement, camelCase contract, LLM outage -> 503."""
from fastapi.testclient import TestClient

from app.llm import LlmError
from app.main import create_app
from app.models import ChatResponse, GuardResult
from tests.conftest import TOKEN

BODY = {"sessionId": "s-1", "messages": [{"role": "user", "content": "hi"}], "profile": {}, "uiLanguage": "en"}


class StubAdvisor:
    def __init__(self, fail: bool = False):
        self.fail = fail
        self.requests = []

    def chat(self, req):
        self.requests.append(req)
        if self.fail:
            raise LlmError("down")
        return ChatResponse(reply="Hello!", detected_language="en", profile={"employees": 3}, stage="COLLECTING",
                            guard=GuardResult(passed=True))


def client(settings, advisor=None):
    return TestClient(create_app(advisor=advisor or StubAdvisor(), settings=settings))


def test_health(settings):
    r = client(settings).get("/health")
    assert r.status_code == 200 and r.json()["status"] == "OK"


def test_chat_without_token_is_401(settings):
    r = client(settings).post("/chat", json=BODY)
    assert r.status_code == 401
    assert r.json()["detail"]["code"] == "UNAUTHORIZED"


def test_chat_with_wrong_token_is_401(settings):
    r = client(settings).post("/chat", json=BODY, headers={"X-Internal-Token": "wrong"})
    assert r.status_code == 401


def test_chat_with_token_returns_camel_case_contract(settings):
    stub = StubAdvisor()
    r = client(settings, stub).post("/chat", json=BODY, headers={"X-Internal-Token": TOKEN})
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"reply", "detectedLanguage", "profile", "stage", "recommendation", "quote",
                         "toolTrace", "guard"}
    assert body["guard"] == {"passed": True, "retries": 0, "fallbackUsed": False, "violations": []}
    assert stub.requests[0].session_id == "s-1" and stub.requests[0].ui_language == "en"


def test_empty_server_token_rejects_everything(settings):
    settings.internal_token = ""
    r = client(settings).post("/chat", json=BODY, headers={"X-Internal-Token": ""})
    assert r.status_code == 401


def test_llm_outage_is_503(settings):
    r = client(settings, StubAdvisor(fail=True)).post("/chat", json=BODY, headers={"X-Internal-Token": TOKEN})
    assert r.status_code == 503
    assert r.json()["detail"]["code"] == "LLM_UNAVAILABLE"


def test_invalid_request_is_422(settings):
    r = client(settings).post("/chat", json={"sessionId": "s", "messages": []}, headers={"X-Internal-Token": TOKEN})
    assert r.status_code == 422
