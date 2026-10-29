"""Request/response models for POST /chat (camelCase JSON, snake_case Python)."""
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

Stage = Literal["COLLECTING", "RECOMMENDED", "CUSTOMISING", "READY_FOR_CHECKOUT", "OUT_OF_SCOPE"]


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ChatMessage(CamelModel):
    role: Literal["user", "assistant"]
    content: str = Field(max_length=4000)


class ConversationState(CamelModel):
    """What Spring remembers between turns (the service itself stores nothing)."""
    stage: Stage | None = None
    recommendation: dict[str, Any] | None = None
    quote: dict[str, Any] | None = None


class ChatRequest(CamelModel):
    session_id: str = Field(min_length=1, max_length=64)
    messages: list[ChatMessage] = Field(min_length=1)
    profile: dict[str, Any] = Field(default_factory=dict)
    ui_language: Literal["en", "hi"] | None = None
    state: ConversationState | None = None


class ToolTraceEntry(CamelModel):
    tool: str
    args: dict[str, Any]
    ok: bool
    ms: int


class GuardResult(CamelModel):
    passed: bool
    retries: int = 0
    fallback_used: bool = False
    violations: list[str] = Field(default_factory=list)


class ChatResponse(CamelModel):
    reply: str
    detected_language: str
    profile: dict[str, Any]
    stage: Stage
    recommendation: dict[str, Any] | None = None
    quote: dict[str, Any] | None = None
    tool_trace: list[ToolTraceEntry] = Field(default_factory=list)
    guard: GuardResult
