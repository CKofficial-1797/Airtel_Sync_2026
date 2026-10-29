"""Connectivity check: prints only "OK <model>" (never the key). Run from ai-service/:
    .venv/Scripts/python scripts/check_gemini.py      (Windows)
    .venv/bin/python scripts/check_gemini.py          (macOS/Linux)
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from google import genai  # noqa: E402
from google.genai import errors, types  # noqa: E402

from app.config import get_settings  # noqa: E402


def main() -> int:
    s = get_settings()
    if not s.gemini_api_key or s.gemini_api_key.startswith("your-"):
        print("FAILED: GEMINI_API_KEY is not set in ai-service/.env")
        return 1
    if not s.internal_token or s.internal_token.startswith("change-me"):
        print("WARNING: INTERNAL_TOKEN is not set in ai-service/.env")
    client = genai.Client(api_key=s.gemini_api_key)
    try:
        client.models.generate_content(
            model=s.gemini_model,
            contents="Reply with the single word OK.",
            config=types.GenerateContentConfig(temperature=0),
        )
        print("OK", s.gemini_model)
        return 0
    except errors.APIError as e:
        print(f"FAILED: {s.gemini_model} -> {e.code} {e.status}")
        try:
            flash = sorted(m.name for m in client.models.list() if "flash" in (m.name or ""))
            print("Available flash models on this key:")
            for name in flash:
                print("  ", name)
        except errors.APIError as e2:
            print(f"Could not list models: {e2.code} {e2.status}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
