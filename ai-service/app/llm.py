"""Thin LLM layer. The advisor talks to the `Llm` protocol, so tests can inject a scripted fake.

History is provider-neutral (UserText / ModelTurn / ToolResults / SystemNote). GeminiLlm converts it to
google-genai Content objects and uses MANUAL function calling: the model only proposes calls; our code
executes, logs and guards them.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Protocol, Union

from google import genai
from google.genai import errors, types

from app.config import Settings

log = logging.getLogger("advisor.llm")


@dataclass
class FunctionCallRequest:
    name: str
    args: dict[str, Any]
    id: str | None = None


@dataclass
class LlmTurn:
    text: str
    function_calls: list[FunctionCallRequest] = field(default_factory=list)
    raw: Any = None  # provider content, replayed as-is (keeps Gemini thought signatures)


@dataclass
class UserText:
    text: str


@dataclass
class ModelTurn:
    text: str
    function_calls: list[FunctionCallRequest] = field(default_factory=list)
    raw: Any = None


@dataclass
class ToolResults:
    results: list[tuple[str, str | None, dict[str, Any]]]  # (tool name, call id, response)


@dataclass
class SystemNote:
    text: str


HistoryItem = Union[UserText, ModelTurn, ToolResults, SystemNote]


class LlmError(Exception):
    """The model could not be reached after retries."""


class Llm(Protocol):
    def generate(self, system: str, history: list[HistoryItem], tools: list[dict[str, Any]]) -> LlmTurn: ...


class GeminiLlm:
    RETRYABLE = {429, 500, 503}

    def __init__(self, settings: Settings, client: genai.Client | None = None,
                 sleep: Callable[[float], None] = time.sleep):
        self._settings = settings
        self._client = client or genai.Client(
            api_key=settings.gemini_api_key,
            http_options=types.HttpOptions(timeout=int(settings.gemini_timeout_s * 1000)),
        )
        self._sleep = sleep

    def generate(self, system: str, history: list[HistoryItem], tools: list[dict[str, Any]]) -> LlmTurn:
        config = types.GenerateContentConfig(
            system_instruction=system,
            temperature=self._settings.temperature,
            tools=[types.Tool(function_declarations=[
                types.FunctionDeclaration(name=t["name"], description=t["description"],
                                          parameters_json_schema=t["parameters"])
                for t in tools
            ])],
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        )
        contents = self._contents(history)
        attempts = self._settings.gemini_max_attempts
        for attempt in range(1, attempts + 1):
            try:
                resp = self._client.models.generate_content(
                    model=self._settings.gemini_model, contents=contents, config=config)
                break
            except errors.APIError as e:
                if e.code in self.RETRYABLE and attempt < attempts:
                    log.warning("Gemini %s, retry %d/%d", e.code, attempt, attempts - 1)
                    self._sleep(2 ** attempt)
                    continue
                raise LlmError(f"Gemini error {e.code} {e.status}") from e
            except Exception as e:  # network/timeouts from the SDK's HTTP layer
                if attempt < attempts:
                    log.warning("Gemini call failed (%s), retry %d/%d", type(e).__name__, attempt, attempts - 1)
                    self._sleep(2 ** attempt)
                    continue
                raise LlmError(f"Gemini unreachable: {type(e).__name__}") from e

        content = resp.candidates[0].content if resp.candidates else None
        parts = (content.parts or []) if content else []
        text = "".join(p.text for p in parts if p.text and not p.thought)
        calls = [FunctionCallRequest(fc.name, dict(fc.args or {}), fc.id) for fc in (resp.function_calls or [])]
        return LlmTurn(text=text.strip(), function_calls=calls, raw=content)

    @staticmethod
    def _contents(history: list[HistoryItem]) -> list[types.Content]:
        out: list[types.Content] = []
        for item in history:
            if isinstance(item, UserText):
                out.append(types.Content(role="user", parts=[types.Part.from_text(text=item.text)]))
            elif isinstance(item, SystemNote):
                note = "[Note from the system, not from the customer] " + item.text
                out.append(types.Content(role="user", parts=[types.Part.from_text(text=note)]))
            elif isinstance(item, ModelTurn):
                if item.raw is not None:
                    out.append(item.raw)
                else:
                    out.append(types.Content(role="model", parts=[types.Part.from_text(text=item.text or "...")]))
            elif isinstance(item, ToolResults):
                parts = [types.Part(function_response=types.FunctionResponse(name=name, response=resp, id=call_id))
                         for name, call_id, resp in item.results]
                out.append(types.Content(role="user", parts=parts))
        return out
