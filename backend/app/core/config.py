"""Single source of application configuration (environment / ``.env``)."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import URL, make_url

BACKEND_DIR = Path(__file__).resolve().parents[2]

# Passwords that must never be accepted in production.
_PLACEHOLDER_PASSWORDS = {"", "change-me-local-dev-password", "changeme", "password", "postgres"}

Environment = Literal["development", "testing", "production"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        # Absolute path so the location of the working directory does not matter.
        env_file=BACKEND_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
        hide_input_in_errors=True,  # never echo secret values inside validation errors
    )

    # Application
    app_name: str = "wasteinfoAI API"
    app_version: str = "0.1.0"
    environment: Environment = "development"
    debug: bool = False
    api_v1_prefix: str = "/api/v1"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = "INFO"
    log_json: bool = True

    # PostgreSQL
    postgres_host: str = "localhost"
    postgres_port: int = Field(default=5432, ge=1, le=65535)
    postgres_db: str = "wasteinfoai"
    postgres_user: str = "wasteinfoai"
    postgres_password: SecretStr = SecretStr("")
    database_url: SecretStr | None = None

    db_pool_size: int = Field(default=5, ge=1)
    db_max_overflow: int = Field(default=10, ge=0)
    db_pool_recycle_seconds: int = Field(default=1800, ge=-1)
    db_connect_timeout_seconds: int = Field(default=5, ge=1)

    # CORS (comma separated string; exposed as a list via ``cors_origins``)
    cors_allowed_origins: str = "http://localhost:3000,http://127.0.0.1:3000"
    cors_allow_credentials: bool = False

    # ------------------------------------------------------------------ validators
    @field_validator("api_v1_prefix")
    @classmethod
    def _validate_prefix(cls, value: str) -> str:
        value = value.strip()
        if not value.startswith("/") or (len(value) > 1 and value.endswith("/")):
            raise ValueError("API_V1_PREFIX must start with '/' and must not end with '/'")
        return value

    @field_validator("database_url", mode="before")
    @classmethod
    def _normalize_database_url(cls, value):
        if value is None:
            return None
        raw = value.get_secret_value() if isinstance(value, SecretStr) else str(value)
        raw = raw.strip()
        if not raw:
            return None
        # Accept common aliases but always run on the psycopg 3 driver.
        for prefix in ("postgresql://", "postgres://"):
            if raw.startswith(prefix):
                raw = "postgresql+psycopg://" + raw[len(prefix):]
        if not raw.startswith("postgresql+psycopg://"):
            raise ValueError("DATABASE_URL must use the 'postgresql+psycopg://' scheme")
        return raw

    @model_validator(mode="after")
    def _validate_environment_rules(self) -> "Settings":
        origins = self.cors_origins
        if self.cors_allow_credentials and "*" in origins:
            raise ValueError("CORS_ALLOWED_ORIGINS cannot contain '*' when credentials are allowed")

        if self.environment == "production":
            problems: list[str] = []
            if self.debug:
                problems.append("DEBUG must be false in production")
            if "*" in origins:
                problems.append("CORS_ALLOWED_ORIGINS must not contain '*' in production")
            if self.database_url is None:
                if self.postgres_password.get_secret_value() in _PLACEHOLDER_PASSWORDS:
                    problems.append("POSTGRES_PASSWORD must be set to a strong, non-placeholder value")
            if problems:
                raise ValueError("Invalid production configuration: " + "; ".join(problems))
        return self

    # ------------------------------------------------------------------ helpers
    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in self.cors_allowed_origins.split(",") if o.strip()]

    @property
    def sqlalchemy_url(self) -> URL:
        """SQLAlchemy URL object (password is escaped correctly by SQLAlchemy)."""
        if self.database_url is not None:
            return make_url(self.database_url.get_secret_value())
        return URL.create(
            drivername="postgresql+psycopg",
            username=self.postgres_user,
            password=self.postgres_password.get_secret_value() or None,
            host=self.postgres_host,
            port=self.postgres_port,
            database=self.postgres_db,
        )

    @property
    def safe_database_url(self) -> str:
        """Database URL with the password masked; safe for logs."""
        return self.sqlalchemy_url.render_as_string(hide_password=True)

    @property
    def docs_enabled(self) -> bool:
        return self.environment != "production"


@lru_cache
def get_settings() -> Settings:
    return Settings()
