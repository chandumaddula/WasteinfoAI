"""FastAPI application factory. Importing this module never connects to PostgreSQL."""
from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.router import api_router
from app.core.config import Settings, get_settings
from app.core.errors import register_exception_handlers
from app.core.logging_config import setup_logging
from app.core.middleware import RequestContextMiddleware
from app.database.session import dispose_engine
from app.schemas.health import ServiceInfo

logger = logging.getLogger("app")

DESCRIPTION = (
    "Backend API for **wasteinfoAI**, an offline-first waste identification, reporting "
    "and cleanup coordination system. Clients access data only through this API."
)


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    setup_logging(settings.log_level, settings.log_json)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        logger.info("Starting %s v%s (%s)", settings.app_name, settings.app_version, settings.environment)
        yield  # no database connection is required to start
        dispose_engine()
        logger.info("Shutdown complete")

    app = FastAPI(
        title=settings.app_name,
        description=DESCRIPTION,
        version=settings.app_version,
        debug=settings.debug,
        lifespan=lifespan,
        docs_url="/docs" if settings.docs_enabled else None,
        redoc_url="/redoc" if settings.docs_enabled else None,
        openapi_url="/openapi.json" if settings.docs_enabled else None,
    )

    register_exception_handlers(app)

    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_credentials=settings.cors_allow_credentials,
            allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", "X-Request-ID"],
            expose_headers=["X-Request-ID"],
        )
    # Added last => outermost, so every response (even CORS preflight) carries a request id.
    app.add_middleware(RequestContextMiddleware)

    app.include_router(api_router, prefix=settings.api_v1_prefix)

    @app.get("/", response_model=ServiceInfo, tags=["root"], summary="Service information")
    def root() -> ServiceInfo:
        return ServiceInfo(
            service=settings.app_name,
            version=settings.app_version,
            api_prefix=settings.api_v1_prefix,
            docs_url="/docs" if settings.docs_enabled else None,
            health_url=f"{settings.api_v1_prefix}/health",
        )

    return app


app = create_app()
