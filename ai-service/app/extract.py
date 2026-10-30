"""KYC document extraction with Gemini vision + structured output (one JSON schema per document type).

The model only transcribes what is printed. It never infers a missing value: blank fields come back null
(K01's blank Udyam number must stay null). Images are processed in memory and never written to disk.
Extracted values are never logged.
"""
from __future__ import annotations

import json
import logging
import time
from typing import Any, Callable, Protocol

from google import genai
from google.genai import errors, types

from app.config import Settings
from app.llm import LlmError

log = logging.getLogger("advisor.extract")

DOC_TYPES = ["GST_CERTIFICATE", "UDYAM_CERTIFICATE", "SIGNATORY_ID", "ADDRESS_PROOF"]

FIELDS: dict[str, dict[str, str]] = {
    "GST_CERTIFICATE": {
        "gstin": "GSTIN, exactly as printed (15 characters)",
        "legalName": "Legal name, exactly as printed, including any '(Prop. ...)' part",
        "tradeName": "Trade name",
        "constitution": "Constitution of business",
        "principalAddress": "Address of principal place of business, wrapped lines joined with one space",
        "dateOfLiability": "Date of liability, YYYY-MM-DD",
        "registrationType": "Type of registration",
    },
    "UDYAM_CERTIFICATE": {
        "udyamNumber": "Udyam registration number exactly as printed; null if the field is blank",
        "enterpriseName": "Enterprise name",
        "majorActivity": "Major activity",
        "city": "City",
        "state": "State",
        "dateOfRegistration": "Date of registration, YYYY-MM-DD",
    },
    "SIGNATORY_ID": {
        "name": "Name of the person",
        "designation": "Designation",
        "forBusiness": "The business the person signs for ('For business')",
        "idNumber": "ID number",
        "validTill": "Valid till date, YYYY-MM-DD",
    },
    "ADDRESS_PROOF": {
        "documentKind": "Kind of document, e.g. 'Electricity bill'",
        "consumerNumber": "Consumer number",
        "billedTo": "Name the bill is addressed to ('Billed to')",
        "supplyAddress": "Supply address, wrapped lines joined with one space",
        "billDate": "Bill date, YYYY-MM-DD",
        "amountDueInr": "Amount due in rupees as a number (digits only)",
    },
}


def response_schema(doc_type: str) -> dict[str, Any]:
    props: dict[str, Any] = {
        "detectedDocType": {
            "type": "string",
            "enum": DOC_TYPES + ["OTHER"],
            "description": "Which kind of document this image actually is",
        }
    }
    for name, description in FIELDS[doc_type].items():
        kind = "number" if name == "amountDueInr" else "string"
        props[name] = {"type": [kind, "null"], "description": description}
    return {"type": "object", "properties": props, "required": list(props)}


PROMPT = """You are reading ONE scanned business document for KYC. Document slot: {doc_type}.
Rules:
- Copy every value EXACTLY as printed: same spelling, capitalisation and punctuation. Do not correct anything.
- If a field is empty, missing or illegible on THIS document, return null. Never infer or guess a value from
  other fields, other documents or general knowledge.
- Addresses that wrap onto a second line: join the lines with a single space. Re-join a word split across the
  line break only when it is obvious (e.g. "Maharashtr" + "a" -> "Maharashtra").
- Dates: convert to YYYY-MM-DD.
- Ignore the diagonal "SYNTHETIC SPECIMEN - NOT A VALID DOCUMENT" watermark, the page title and footer text.
- detectedDocType: GST_CERTIFICATE, UDYAM_CERTIFICATE, SIGNATORY_ID (an ID / authorised signatory card),
  ADDRESS_PROOF (e.g. an electricity bill) or OTHER.
Return only the JSON object defined by the schema."""


class VisionClient(Protocol):
    def extract(self, image_png: bytes, doc_type: str) -> tuple[dict[str, Any], str]: ...


class GeminiExtractor:
    """Calls Gemini with the image + schema. Retries 429/5xx with backoff (max gemini_max_attempts tries)."""

    RETRYABLE = {429, 500, 503}

    def __init__(self, settings: Settings, client: genai.Client | None = None,
                 sleep: Callable[[float], None] = time.sleep):
        self._settings = settings
        self._client = client or genai.Client(
            api_key=settings.gemini_api_key,
            http_options=types.HttpOptions(timeout=int(settings.gemini_timeout_s * 1000)),
        )
        self._sleep = sleep

    def extract(self, image_png: bytes, doc_type: str) -> tuple[dict[str, Any], str]:
        model = self._settings.gemini_extract_model
        config = types.GenerateContentConfig(
            temperature=0,
            response_mime_type="application/json",
            response_json_schema=response_schema(doc_type),
        )
        contents = [types.Part.from_bytes(data=image_png, mime_type="image/png"), PROMPT.format(doc_type=doc_type)]
        attempts = self._settings.gemini_max_attempts
        for attempt in range(1, attempts + 1):
            try:
                resp = self._client.models.generate_content(model=model, contents=contents, config=config)
                return json.loads(resp.text or "{}"), model
            except errors.APIError as e:
                if e.code in self.RETRYABLE and attempt < attempts:
                    log.warning("Gemini extract %s, retry %d/%d", e.code, attempt, attempts - 1)
                    self._sleep(2 ** attempt)
                    continue
                raise LlmError(f"Gemini error {e.code} {e.status}") from e
            except json.JSONDecodeError as e:
                raise LlmError("Gemini returned invalid JSON") from e
            except Exception as e:  # network/timeouts from the SDK's HTTP layer
                if attempt < attempts:
                    self._sleep(2 ** attempt)
                    continue
                raise LlmError(f"Gemini unreachable: {type(e).__name__}") from e
        raise LlmError("Gemini extraction failed")


def clean_fields(raw: dict[str, Any], doc_type: str) -> dict[str, Any]:
    """Keeps only the schema fields; trims strings; empty strings become null."""
    out: dict[str, Any] = {}
    for name in FIELDS[doc_type]:
        value = raw.get(name)
        if isinstance(value, str):
            value = " ".join(value.split()) or None
        out[name] = value
    return out
