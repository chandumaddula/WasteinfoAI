import logging
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Response, status
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.config import Settings, get_settings
from app.database.session import get_db
from app.schemas.health import DependencyChecks, HealthResponse, ReadinessResponse

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/health", tags=["health"])


@router.get("", response_model=HealthResponse, summary="Liveness check")
def health(settings: Settings = Depends(get_settings)) -> HealthResponse:
    """Returns 200 while the API process is running. Does not touch the database."""
    return HealthResponse(
        service=settings.app_name,
        version=settings.app_version,
        timestamp=datetime.now(timezone.utc),
    )


@router.get(
    "/ready",
    response_model=ReadinessResponse,
    summary="Readiness check (PostgreSQL)",
    responses={503: {"model": ReadinessResponse, "description": "Database unavailable"}},
)
def readiness(
    response: Response,
    settings: Settings = Depends(get_settings),
    db: Session = Depends(get_db),
) -> ReadinessResponse:
    """Runs ``SELECT 1`` against PostgreSQL. 200 when reachable, 503 otherwise."""
    try:
        db.execute(text("SELECT 1"))
        db_status = "ok"
    except SQLAlchemyError as exc:
        # Log the failure class only; the client receives no connection details.
        logger.error("Readiness check failed: database unavailable (%s)", type(exc).__name__)
        db_status = "unavailable"
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE

    return ReadinessResponse(
        status="ready" if db_status == "ok" else "not_ready",
        service=settings.app_name,
        version=settings.app_version,
        timestamp=datetime.now(timezone.utc),
        checks=DependencyChecks(database=db_status),
    )
