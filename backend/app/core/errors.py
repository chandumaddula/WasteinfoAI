"""Consistent JSON error format and centralized exception handlers.

Error body::

    {"error": {"code": "...", "message": "...", "request_id": "...", "details": [...]}}
"""
from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("app.errors")

_STATUS_CODES = {
    400: "bad_request",
    401: "unauthorized",
    403: "forbidden",
    404: "not_found",
    405: "method_not_allowed",
    409: "conflict",
    422: "validation_error",
    429: "too_many_requests",
    503: "service_unavailable",
}


class AppError(Exception):
    """Base class for expected application errors (extend for later modules)."""

    status_code = 400
    code = "application_error"

    def __init__(self, message: str, *, status_code: int | None = None,
                 code: str | None = None, details: list[Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        if status_code is not None:
            self.status_code = status_code
        if code is not None:
            self.code = code
        self.details = details


def _request_id(request: Request) -> str | None:
    return getattr(request.state, "request_id", None)


def error_response(request: Request, status_code: int, code: str, message: str,
                   details: list[Any] | None = None,
                   headers: dict[str, str] | None = None) -> JSONResponse:
    body: dict[str, Any] = {"code": code, "message": message, "request_id": _request_id(request)}
    if details:
        body["details"] = details
    headers = dict(headers or {})
    rid = _request_id(request)
    if rid:
        headers.setdefault("X-Request-ID", rid)
    return JSONResponse(status_code=status_code, content={"error": body}, headers=headers)


async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
    logger.warning("Application error: %s", exc.message, extra={"code": exc.code})
    return error_response(request, exc.status_code, exc.code, exc.message, exc.details)


async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    code = _STATUS_CODES.get(exc.status_code, "http_error")
    message = exc.detail if isinstance(exc.detail, str) else "Request could not be processed"
    return error_response(request, exc.status_code, code, message,
                          headers=getattr(exc, "headers", None))


async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    # Only location / message / type are returned. The submitted input is never echoed.
    details = [
        {"loc": [str(p) for p in err.get("loc", ())], "msg": err.get("msg", ""), "type": err.get("type", "")}
        for err in exc.errors()
    ]
    return error_response(request, 422, "validation_error", "Request validation failed", details)


async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    # Full diagnostics go to the log (credentials masked by the log formatter); never to the client.
    logger.error("Unhandled exception on %s %s", request.method, request.url.path, exc_info=exc)
    return error_response(request, 500, "internal_server_error", "An unexpected error occurred")


def register_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(AppError, app_error_handler)  # type: ignore[arg-type]
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)  # type: ignore[arg-type]
    app.add_exception_handler(RequestValidationError, validation_exception_handler)  # type: ignore[arg-type]
    app.add_exception_handler(Exception, unhandled_exception_handler)
