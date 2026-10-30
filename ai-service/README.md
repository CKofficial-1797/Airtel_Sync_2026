# ai-service: Accel Business AI advisor

Stateless FastAPI service. Gemini (google-genai, **manual** function calling) gathers facts, calls the
Spring rules engine as tools, and explains the result. **All products, eligibility, quantities and prices
come from Spring**; `app/guard.py` blocks any reply with an amount or product not found in tool results.

```
app/main.py            GET /health, POST /chat (requires X-Internal-Token)
app/advisor.py         tool-calling loop (max 6 model calls/turn), guard retry + template fallback, stage logic
app/prompts.py         the system prompt (single readable file)
app/tools.py           tool declarations + dispatcher (save_profile, lookup_gstin, recommend_bundle,
                       get_quote, check_eligibility, mark_off_topic)
app/guard.py           grounding guard + deterministic EN/HI fallback template
app/llm.py             Llm protocol + GeminiLlm (retries 429/5xx with backoff, 3 tries)
app/backend_client.py  httpx client for Spring (10 s timeout, X-Internal-Token)
app/reference.py       cities + catalogue names for the prompt (no prices)
tests/                 pytest, no network (fake LLM + respx-mocked Spring)
evals/                 15 live scenarios (run manually; uses real Gemini)
scripts/check_gemini.py  one-call connectivity check
```

## Setup

```powershell
# Windows PowerShell (from ai-service/)
python -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt
Copy-Item .env.example .env   # then fill GEMINI_API_KEY and INTERNAL_TOKEN
```

```bash
# macOS / Linux (from ai-service/)
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
cp .env.example .env          # then fill GEMINI_API_KEY and INTERNAL_TOKEN
```

Generate a token: `python -c "import secrets; print(secrets.token_hex(16))"`. Put the same value in Spring
(`app.ai-service.token` in `backend/src/main/resources/application-local.yml`, or env `INTERNAL_TOKEN`).

## Run

```bash
# Windows: .venv\Scripts\python -m uvicorn app.main:app --reload --port 8000
.venv/bin/python -m uvicorn app.main:app --reload --port 8000
```

## Test (no network, no Gemini calls)

```bash
.venv/bin/python -m pytest -q        # Windows: .venv\Scripts\python -m pytest -q
```

## Live evals (uses Gemini quota: ~2-4 calls per turn, ~25 turns for all 15)

Spring on :8080 and ai-service on :8000 must be running.

```bash
.venv/bin/python evals/run_scenarios.py              # all
.venv/bin/python evals/run_scenarios.py --only 1,11  # subset
```

## Environment (.env)

| Key | Default | Purpose |
|---|---|---|
| `GEMINI_API_KEY` | - | Google AI Studio key |
| `GEMINI_MODEL` | `gemini-3-flash-preview` | Advisor model |
| `GEMINI_EXTRACT_MODEL` | `gemini-3.1-flash-lite` | Document reading model |
| `BACKEND_BASE_URL` | `http://localhost:8080` | Spring |
| `INTERNAL_TOKEN` | - | Shared secret, required on /chat and sent to Spring |
| `BACKEND_TIMEOUT_S` / `GEMINI_TIMEOUT_S` | 10 / 30 | Timeouts |
