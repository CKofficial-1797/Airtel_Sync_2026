"""FastAPI entry point: GET /health, POST /chat (requires X-Internal-Token). Stateless."""
from __future__ import annotations

import logging
import secrets

from fastapi import Depends, FastAPI, Header, HTTPException

from app.advisor import Advisor, build_default_advisor
from app.config import Settings, get_settings
from app.llm import LlmError
from app.models import ChatRequest, ChatResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("advisor.api")


def create_app(advisor: Advisor | None = None, settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    app = FastAPI(title="Udyam Digital advisor (ai-service)", version="0.4.0")
    holder: dict[str, Advisor | None] = {"advisor": advisor}

    def get_advisor() -> Advisor:
        if holder["advisor"] is None:
            holder["advisor"] = build_default_advisor(settings)
        return holder["advisor"]

    def require_token(x_internal_token: str | None = Header(default=None)) -> None:
        expected = settings.internal_token
        if not expected or not x_internal_token or not secrets.compare_digest(x_internal_token, expected):
            raise HTTPException(status_code=401,
                                detail={"code": "UNAUTHORIZED", "message": "Missing or invalid X-Internal-Token"})

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "OK", "model": settings.gemini_model}

    @app.post("/chat", response_model=ChatResponse, dependencies=[Depends(require_token)])
    def chat(req: ChatRequest) -> ChatResponse:
        try:
            return get_advisor().chat(req)
        except LlmError as e:
            log.warning("session=%s LLM unavailable: %s", req.session_id[:8], e)
            raise HTTPException(status_code=503,
                                detail={"code": "LLM_UNAVAILABLE", "message": "The language model is unavailable"})

    return app


app = create_app()
