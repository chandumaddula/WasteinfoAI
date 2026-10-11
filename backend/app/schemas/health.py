from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field


class ServiceInfo(BaseModel):
    service: str
    version: str
    api_prefix: str
    docs_url: str | None = Field(description="Swagger UI path (null when disabled)")
    health_url: str


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    service: str
    version: str
    timestamp: datetime


class DependencyChecks(BaseModel):
    database: Literal["ok", "unavailable"]


class ReadinessResponse(BaseModel):
    status: Literal["ready", "not_ready"]
    service: str
    version: str
    timestamp: datetime
    checks: DependencyChecks
