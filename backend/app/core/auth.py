"""Authentication dependencies.

Identity is established through bearer tokens that are validated against the
``user_tokens`` table. The dependency is intentionally a thin, overridable seam so
that tests can substitute a pre-authenticated user without touching the database.

Client-provided ``user_id`` or ``role`` claims are never trusted: every request's
owner and role are derived from the authenticated session.
"""
from __future__ import annotations

import logging
import uuid
from typing import Annotated, Any

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Session

from app.database.session import get_db
from app.models.user import User

logger = logging.getLogger("app.auth")

# Header name used to pass the bearer token.
AUTH_HEADER = "authorization"
BEARER_SCHEME = "bearer"


class AuthenticatedUser:
    """Lightweight, immutable container for the authenticated user.

    FastAPI dependencies return this object rather than the raw SQLAlchemy model so
    that route handlers and services receive a stable, serializable identity.
    """

    __slots__ = ("id", "role", "email", "institution_id", "display_name")

    def __init__(self, user: User) -> None:
        self.id = user.id
        self.role = user.role
        self.email = user.email
        self.institution_id = user.institution_id
        self.display_name = user.display_name

    def __repr__(self) -> str:
        return f"<AuthenticatedUser(id={self.id!r} role={self.role.value!r})>"


def _extract_bearer_token(authorization: str | None) -> str | None:
    """Extract the token from an ``Authorization: Bearer <token>`` header."""
    if not authorization:
        return None
    parts = authorization.split()
    if len(parts) != 2 or parts[0].lower() != BEARER_SCHEME:
        return None
    token = parts[1].strip()
    return token or None


async def get_current_user(
    db: Session = Depends(get_db),
    authorization: Annotated[str | None, Header(alias="Authorization")] = None,
) -> AuthenticatedUser:
    """Validate the bearer token and return the authenticated user.

    Override this dependency in tests to inject a pre-authenticated user.
    """
    token = _extract_bearer_token(authorization)
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or malformed Authorization header",
            headers={"WWW-Authenticate": "Bearer"},
        )

    user = await _load_user_by_token(db, token)
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User account is disabled",
        )
    return AuthenticatedUser(user)


async def get_current_user_optional(
    db: Session = Depends(get_db),
    authorization: Annotated[str | None, Header(alias="Authorization")] = None,
) -> AuthenticatedUser | None:
    """Like :func:`get_current_user` but returns ``None`` instead of raising."""
    token = _extract_bearer_token(authorization)
    if not token:
        return None
    user = await _load_user_by_token(db, token)
    if user is None or not user.is_active:
        return None
    return AuthenticatedUser(user)


import hashlib
import hmac
import os
from datetime import datetime, timezone, timedelta


def hash_password(password: str) -> str:
    """Hash a password using PBKDF2-HMAC-SHA256 with a random salt."""
    salt = os.urandom(16)
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 100000)
    return f"{salt.hex()}${key.hex()}"


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verify a plain password against the stored PBKDF2 hash."""
    try:
        salt_hex, key_hex = hashed_password.split("$", 1)
        salt = bytes.fromhex(salt_hex)
        key = bytes.fromhex(key_hex)
        computed = hashlib.pbkdf2_hmac("sha256", plain_password.encode("utf-8"), salt, 100000)
        return hmac.compare_digest(key, computed)
    except Exception:
        return False


def issue_user_token(
    db: Session,
    user_id: str | uuid.UUID,
    *,
    expires_in_days: int | None = 30,
) -> Any:
    """Issue a new active Bearer token for a user."""
    from app.models.user import UserToken

    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(days=expires_in_days) if expires_in_days is not None else None
    token_val = str(uuid.uuid4())
    user_token = UserToken(
        id=str(uuid.uuid4()),
        user_id=str(user_id),
        token=token_val,
        expires_at=expires_at,
        is_active=True,
        created_at=now,
    )
    db.add(user_token)
    db.commit()
    db.refresh(user_token)
    return user_token


async def _load_user_by_token(db: Session, token: str) -> User | None:
    """Look up the user associated with a bearer token.

    The real implementation queries the ``user_tokens`` table. The token value is
    never logged.
    """
    from app.models.user import UserToken

    try:
        token_uuid = uuid.UUID(token)
    except (ValueError, AttributeError):
        return None

    now = datetime.now(timezone.utc)
    stmt = (
        select(User, UserToken)
        .join(UserToken, UserToken.user_id == User.id)
        .where(
            (UserToken.token == str(token_uuid)) | (UserToken.token == token_uuid),
            UserToken.is_active.is_(True),
        )
    )
    result = await db.execute(stmt) if _is_async_session(db) else db.execute(stmt)
    row = result.first()
    if row is None:
        return None

    user, user_token = row
    if user_token.expires_at is not None:
        expires_at = user_token.expires_at
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=timezone.utc)
        if expires_at <= now:
            return None

    return user


def _is_async_session(db: object) -> bool:
    return isinstance(db, AsyncSession)


# Re-exported for convenience; services import from here.
CurrentUser = Annotated[AuthenticatedUser, Depends(get_current_user)]
CurrentUserOptional = Annotated[AuthenticatedUser | None, Depends(get_current_user_optional)]
