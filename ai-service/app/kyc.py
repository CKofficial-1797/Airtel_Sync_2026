"""POST /extract pipeline: validate file -> quality (blur/skew) -> Gemini structured extraction."""
from __future__ import annotations

import logging
import time
from typing import Any

from pydantic import Field

from app import quality
from app.config import Settings
from app.extract import DOC_TYPES, VisionClient, clean_fields
from app.models import CamelModel

log = logging.getLogger("advisor.kyc")


class QualityOut(CamelModel):
    blur_score: float
    is_blurry: bool
    skew_deg: float
    deskewed: bool


class ExtractResponse(CamelModel):
    status: str  # OK | UNREADABLE | WRONG_DOC_TYPE
    doc_type: str
    detected_doc_type: str | None = None
    quality: QualityOut
    fields: dict[str, Any] | None = None
    model: str | None = None
    ms: int = Field(default=0)


class KycExtractor:
    def __init__(self, settings: Settings, vision: VisionClient):
        self.settings = settings
        self.vision = vision

    def run(self, data: bytes, doc_type: str) -> ExtractResponse:
        if doc_type not in DOC_TYPES:
            raise quality.InvalidImage(f"docType must be one of {DOC_TYPES}")
        started = time.perf_counter()
        img = quality.decode(data, self.settings.max_upload_bytes)
        q, prepared = quality.assess(img, self.settings.blur_threshold, self.settings.skew_threshold_deg)
        quality_out = QualityOut(blur_score=q.blur_score, is_blurry=q.is_blurry, skew_deg=q.skew_deg, deskewed=q.deskewed)

        if q.is_blurry:  # never guess fields from an unreadable photo
            response = ExtractResponse(status="UNREADABLE", doc_type=doc_type, quality=quality_out)
        else:
            raw, model = self.vision.extract(quality.encode_png(prepared), doc_type)
            detected = raw.get("detectedDocType")
            if detected and detected != doc_type:
                response = ExtractResponse(status="WRONG_DOC_TYPE", doc_type=doc_type, detected_doc_type=detected,
                                           quality=quality_out, model=model)
            else:
                response = ExtractResponse(status="OK", doc_type=doc_type, detected_doc_type=detected or doc_type,
                                           quality=quality_out, fields=clean_fields(raw, doc_type), model=model)
        response.ms = int((time.perf_counter() - started) * 1000)
        log.info("extract docType=%s status=%s blur=%.0f skew=%.1f ms=%d",
                 doc_type, response.status, q.blur_score, q.skew_deg, response.ms)  # never the values
        return response
