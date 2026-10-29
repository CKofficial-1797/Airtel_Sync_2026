"""Shared fixtures: a scripted fake LLM, a mocked Spring backend (respx) and realistic engine payloads.
No test touches the network or Gemini."""
from __future__ import annotations

from typing import Any, Callable

import httpx
import pytest
import respx

from app.advisor import Advisor
from app.backend_client import BackendClient
from app.config import Settings
from app.llm import FunctionCallRequest, LlmTurn
from app.reference import Product, Reference, ReferenceProvider

BACKEND = "http://backend.test"
TOKEN = "test-internal-token-0123456789ab"

PRODUCTS = {
    "P01": ("Biz Fibre 100", "per site", "Connectivity"),
    "P02": ("Biz Fibre 300", "per site", "Connectivity"),
    "P03": ("Biz Fibre 1G", "per site", "Connectivity"),
    "P04": ("Biz 5G AirFiber 100", "per site", "Connectivity"),
    "P07": ("Business Postpaid 299", "per line", "Connectivity"),
    "P11": ("Static IP", "per IP", "Connectivity"),
    "P14": ("Business SMS 10k", "per pack", "Communication"),
    "P15": ("WhatsApp Business API Starter", "per account", "Communication"),
    "P18": ("Website + Domain Starter", "per account", "Cloud & Productivity"),
    "P19": ("Cloud POS & Billing", "per counter", "Cloud & Productivity"),
    "P22": ("Secure Wi-Fi with Guest Network", "per site", "Security"),
    "P23": ("Cloud CCTV", "per camera", "IoT & Physical"),
}


def make_reference() -> Reference:
    return Reference(
        cities={"ludhiana": ("Ludhiana", 2), "bengaluru": ("Bengaluru", 1), "kochi": ("Kochi", 2)},
        products={pid: Product(pid, n, u, c) for pid, (n, u, c) in PRODUCTS.items()},
    )


class FixedReference(ReferenceProvider):
    def __init__(self) -> None:
        self._ref = make_reference()

    def get(self) -> Reference:
        return self._ref


class FakeLlm:
    """Returns scripted turns in order; a script item may be an LlmTurn or a callable(history) -> LlmTurn."""

    def __init__(self, script: list[LlmTurn | Callable[[list], LlmTurn]] | None = None,
                 repeat: LlmTurn | None = None):
        self.script = list(script or [])
        self.repeat = repeat
        self.calls = 0
        self.systems: list[str] = []

    def generate(self, system: str, history: list, tools: list[dict[str, Any]]) -> LlmTurn:
        self.calls += 1
        self.systems.append(system)
        if self.script:
            item = self.script.pop(0)
            return item(history) if callable(item) else item
        if self.repeat is not None:
            return self.repeat
        raise AssertionError("FakeLlm script exhausted")


def call(name: str, **args: Any) -> LlmTurn:
    return LlmTurn(text="", function_calls=[FunctionCallRequest(name, args)])


def say(text: str) -> LlmTurn:
    return LlmTurn(text=text)


def line(pid: str, qty: int, unit_price: int, setup: int = 0, role: str = "INCLUDED") -> dict[str, Any]:
    name, unit, category = PRODUCTS[pid]
    return {"productId": pid, "productName": name, "category": category, "unit": unit, "quantity": qty,
            "unitPriceInr": unit_price, "monthlyTotalInr": unit_price * qty, "setupFeeInr": setup * qty,
            "role": role, "replaces": None, "why": f"why {pid}", "conditional": False}


def quote_payload(lines: list[tuple[str, int, int]], removed: list[dict[str, Any]] | None = None,
                  spend: int | None = None) -> dict[str, Any]:
    q_lines = [{"productId": p, "productName": PRODUCTS[p][0], "unit": PRODUCTS[p][1], "quantity": q,
                "unitPriceInr": u, "monthlyTotalInr": u * q, "setupFeeInr": 0, "minContractMonths": 12,
                "installDays": 5, "conditional": False} for p, q, u in lines]
    monthly = sum(l["monthlyTotalInr"] for l in q_lines)
    return {
        "pricingMode": "MIN", "lines": q_lines, "monthlyTotalInr": monthly, "oneTimeTotalInr": 0,
        "contractMonths": 12, "maxInstallDays": 5, "estimatedActivationDate": "2026-07-20", "conditional": False,
        "removed": removed or [],
        "availableAddOns": [{"productId": "P19", "productName": "Cloud POS & Billing", "unit": "per counter",
                             "unitPriceInr": 599, "defaultQuantity": 1, "setupFeePerUnitInr": 2500,
                             "conditional": False},
                            {"productId": "P23", "productName": "Cloud CCTV", "unit": "per camera",
                             "unitPriceInr": 199, "defaultQuantity": 2, "setupFeePerUnitInr": 1800,
                             "conditional": False}],
        "ineligibleProducts": [{"productId": "P15", "productName": "WhatsApp Business API Starter",
                                "eligible": False, "conditional": False, "condition": None,
                                "reasons": [{"code": "GSTIN_REQUIRED",
                                             "message": "WhatsApp Business API Starter needs an active GSTIN"}]}],
        "comparison": None if spend is None else {"currentSpendInr": spend, "newMonthlyInr": monthly,
                                                  "differenceInr": monthly - spend},
        "notes": ["Prices as per Accel Business catalogue (digital price = catalogue minimum)"],
    }


def kirana_recommendation() -> dict[str, Any]:
    """Shape of POST /api/recommend for: Kirana store, Ludhiana (tier 2), 3 staff, 1 shop, no GST."""
    return {
        "resolvedProfile": {"businessType": "Kirana store", "segment": "Micro retail & services",
                            "city": "Ludhiana", "cityTier": 2, "gstinStatus": "NONE", "gstinRegistryStatus": None,
                            "gstinAutofill": None, "warnings": []},
        "segment": "Micro retail & services",
        "included": [line("P01", 1, 999, role="PRIMARY"), line("P07", 2, 299)],
        "optionalAddOns": [line("P19", 1, 599, 2500, "ADD_ON"), line("P23", 2, 199, 1800, "ADD_ON")],
        "excluded": [{"productId": "P15", "productName": "WhatsApp Business API Starter", "eligible": False,
                      "conditional": False, "condition": None,
                      "reasons": [{"code": "GSTIN_REQUIRED",
                                   "message": "WhatsApp Business API Starter needs an active GSTIN"}]}],
        "notes": [],
        "quote": quote_payload([("P01", 1, 999), ("P07", 2, 299)]),
    }


KIRANA_FACTS = {"businessType": "Kirana store", "city": "Ludhiana", "employees": 3, "sites": 1,
                "gstinDeclared": False}


@pytest.fixture
def settings() -> Settings:
    return Settings(backend_base_url=BACKEND, internal_token=TOKEN, gemini_api_key="unused",
                    _env_file=None)


@pytest.fixture
def backend(settings: Settings) -> BackendClient:
    return BackendClient(settings)


@pytest.fixture
def spring():
    with respx.mock(base_url=BACKEND, assert_all_called=False) as mock:
        yield mock


@pytest.fixture
def make_advisor(settings: Settings, backend: BackendClient) -> Callable[[FakeLlm], Advisor]:
    def factory(llm: FakeLlm) -> Advisor:
        return Advisor(llm, backend, settings, FixedReference())
    return factory


def chat_request(text: str, profile: dict[str, Any] | None = None, stage: str | None = None,
                 recommendation: dict[str, Any] | None = None, quote: dict[str, Any] | None = None,
                 history: list[dict[str, str]] | None = None):
    from app.models import ChatRequest
    messages = (history or []) + [{"role": "user", "content": text}]
    state = {"stage": stage, "recommendation": recommendation, "quote": quote}
    return ChatRequest.model_validate({"sessionId": "test-session-1", "messages": messages,
                                       "profile": profile or {}, "state": state})


def json_body(request: httpx.Request) -> Any:
    import json
    return json.loads(request.content)
