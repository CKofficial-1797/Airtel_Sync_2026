"""Reference data injected into the system prompt: known cities and catalogue names (no prices).
Loaded lazily from Spring and cached; retried on the next request if Spring was not reachable."""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field

from app.backend_client import BackendClient, BackendUnavailable

log = logging.getLogger("advisor.reference")

# Common alias → dataset city. Used by save_profile so "Bangalore" resolves before calling the engine.
CITY_ALIASES = {
    "bangalore": "Bengaluru", "bengalooru": "Bengaluru", "bombay": "Mumbai", "calcutta": "Kolkata",
    "madras": "Chennai", "vizag": "Visakhapatnam", "vishakhapatnam": "Visakhapatnam", "baroda": "Vadodara",
    "poona": "Pune", "mysore": "Mysuru", "cochin": "Kochi", "new delhi": "Delhi", "dilli": "Delhi",
}


@dataclass
class Product:
    product_id: str
    name: str
    unit: str
    category: str


@dataclass
class Reference:
    cities: dict[str, tuple[str, int]] = field(default_factory=dict)  # lower name -> (name, tier)
    products: dict[str, Product] = field(default_factory=dict)        # id -> Product

    def canonical_city(self, name: str) -> str | None:
        key = name.strip().lower()
        key = CITY_ALIASES.get(key, key).lower()
        hit = self.cities.get(key)
        return hit[0] if hit else None

    def product_id(self, ref: str) -> str | None:
        """Accepts 'P03', 'p3', or a product name (exact or unique partial, case-insensitive)."""
        r = ref.strip()
        m = re.fullmatch(r"[Pp]0?(\d{1,2})", r)
        if m:
            pid = f"P{int(m.group(1)):02d}"
            return pid if pid in self.products or not self.products else None
        low = r.lower()
        exact = [p.product_id for p in self.products.values() if p.name.lower() == low]
        if exact:
            return exact[0]
        partial = [p.product_id for p in self.products.values() if low in p.name.lower()]
        return partial[0] if len(partial) == 1 else None


class ReferenceProvider:
    def __init__(self, backend: BackendClient):
        self._backend = backend
        self._ref: Reference | None = None

    def get(self) -> Reference:
        if self._ref is not None:
            return self._ref
        try:
            cities = self._backend.cities()
            products = self._backend.products()
        except BackendUnavailable:
            log.warning("Reference data not available yet")
            return Reference()
        if not (cities.ok and products.ok):
            return Reference()
        ref = Reference(
            cities={c["city"].lower(): (c["city"], c["tier"]) for c in cities.body},
            products={p["productId"]: Product(p["productId"], p["productName"], p["unit"], p["category"])
                      for p in products.body},
        )
        self._ref = ref
        log.info("Reference loaded: %d cities, %d products", len(ref.cities), len(ref.products))
        return ref
