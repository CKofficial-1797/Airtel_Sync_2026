"""httpx client for the Spring backend. Every call carries X-Internal-Token."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

import httpx

from app.config import Settings


class BackendUnavailable(Exception):
    """Spring is down, timed out or returned 5xx: the advisor must not guess."""


@dataclass
class BackendResponse:
    status: int
    body: Any

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300


class BackendClient:
    def __init__(self, settings: Settings, client: httpx.Client | None = None):
        self._client = client or httpx.Client(
            base_url=settings.backend_base_url,
            timeout=settings.backend_timeout_s,
            headers={"X-Internal-Token": settings.internal_token},
        )

    def call(self, method: str, path: str, json: Any = None) -> BackendResponse:
        try:
            r = self._client.request(method, path, json=json)
        except httpx.HTTPError as e:
            raise BackendUnavailable(type(e).__name__) from e
        if r.status_code >= 500:
            raise BackendUnavailable(f"HTTP {r.status_code}")
        try:
            body = r.json()
        except ValueError:
            body = None
        return BackendResponse(r.status_code, body)

    def gstin(self, gstin: str) -> BackendResponse:
        return self.call("GET", f"/api/mock/gstin/{quote(gstin, safe='')}")

    def recommend(self, profile: dict[str, Any]) -> BackendResponse:
        return self.call("POST", "/api/recommend", {"profile": profile})

    def quote(self, profile: dict[str, Any], items: list[dict[str, Any]],
              removed_product_ids: list[str]) -> BackendResponse:
        return self.call("POST", "/api/quote",
                         {"profile": profile, "items": items, "removedProductIds": removed_product_ids})

    def eligibility(self, profile: dict[str, Any], bundle_product_ids: list[str]) -> BackendResponse:
        return self.call("POST", "/api/eligibility", {"profile": profile, "bundleProductIds": bundle_product_ids})

    def cities(self) -> BackendResponse:
        return self.call("GET", "/api/reference/cities")

    def products(self) -> BackendResponse:
        return self.call("GET", "/api/products")
