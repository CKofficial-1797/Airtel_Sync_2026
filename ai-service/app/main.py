"""FastAPI entry point: GET /health, POST /chat and POST /extract (both require X-Internal-Token). Stateless."""
from __future__ import annotations

import logging
import secrets

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile

from app.advisor import Advisor, build_default_advisor
from app.config import Settings, get_settings
from app.kyc import ExtractResponse, KycExtractor
from app.llm import LlmError
from app.models import ChatRequest, ChatResponse
from app.quality import InvalidImage

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("advisor.api")


def create_app(advisor: Advisor | None = None, settings: Settings | None = None,
               extractor: KycExtractor | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(title="Udyam Digital ai-service (advisor + KYC extraction)", version="0.6.0")
    holder: dict[str, object | None] = {"advisor": advisor, "extractor": extractor}

    def get_advisor() -> Advisor:
        if holder["advisor"] is None:
            holder["advisor"] = build_default_advisor(settings)
        return holder["advisor"]  # type: ignore[return-value]

    def get_extractor() -> KycExtractor:
        if holder["extractor"] is None:
            from app.extract import GeminiExtractor  # lazily: tests never build the SDK client
            holder["extractor"] = KycExtractor(settings, GeminiExtractor(settings))
        return holder["extractor"]  # type: ignore[return-value]

    def require_token(x_internal_token: str | None = Header(default=None)) -> None:
        expected = settings.internal_token
        if not expected or not x_internal_token or not secrets.compare_digest(x_internal_token, expected):
            raise HTTPException(status_code=401,
                                detail={"code": "UNAUTHORIZED", "message": "Missing or invalid X-Internal-Token"})

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "OK", "model": settings.gemini_model, "extractModel": settings.gemini_extract_model}

    @app.post("/chat", response_model=ChatResponse, dependencies=[Depends(require_token)])
    def chat(req: ChatRequest) -> ChatResponse:
        try:
            return get_advisor().chat(req)
        except LlmError as e:
            log.warning("session=%s LLM unavailable: %s", req.session_id[:8], e)
            raise HTTPException(status_code=503,
                                detail={"code": "LLM_UNAVAILABLE", "message": "The language model is unavailable"})

    @app.post("/extract", response_model=ExtractResponse, dependencies=[Depends(require_token)])
    async def extract(file: UploadFile = File(...), docType: str = Form(...)) -> ExtractResponse:  # noqa: N803
        data = await file.read(settings.max_upload_bytes + 1)  # in memory only; never written to disk
        try:
            return get_extractor().run(data, docType)
        except InvalidImage as e:
            raise HTTPException(status_code=400, detail={"code": "INVALID_IMAGE", "message": str(e)})
        except LlmError as e:
            log.warning("extract docType=%s LLM unavailable: %s", docType, e)
            raise HTTPException(status_code=503,
                                detail={"code": "LLM_UNAVAILABLE", "message": "The extraction model is unavailable"})

    return app


app = create_app()
