"""Settings from environment variables / ai-service/.env (git-ignored)."""
from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

ENV_FILE = Path(__file__).resolve().parent.parent / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ENV_FILE, env_file_encoding="utf-8", extra="ignore")

    gemini_api_key: str = ""
    gemini_model: str = "gemini-3-flash-preview"
    backend_base_url: str = "http://localhost:8080"
    internal_token: str = ""
    backend_timeout_s: float = 10.0
    gemini_timeout_s: float = 30.0
    temperature: float = 0.2
    max_tool_iterations: int = 6
    gemini_max_attempts: int = 3

    # KYC extraction (Phase 6). Thresholds calibrated on the 47 specimen images (scripts/calibrate_quality.py):
    # blur: K04 GST = 1.0, every readable image >= 732; skew: K02 GST = 6.96 deg, all others 0.00 deg.
    gemini_extract_model: str = "gemini-3.1-flash-lite"
    blur_threshold: float = 100.0
    skew_threshold_deg: float = 1.5
    max_upload_bytes: int = 5 * 1024 * 1024


@lru_cache
def get_settings() -> Settings:
    return Settings()
