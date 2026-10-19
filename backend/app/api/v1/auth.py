"""Authentication endpoints for user login, token issuance, and identity inspection."""
from __future__ import annotations

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.auth import (
    AuthenticatedUser,
    CurrentUser,
    hash_password,
    issue_user_token,
    verify_password,
)
from app.database.session import get_db
from app.models.user import User, UserRole
from app.schemas.user import LoginRequest, RegisterRequest, TokenResponse, UserCreate, UserResponse

router = APIRouter()


@router.post(
    "/token",
    response_model=TokenResponse,
    summary="Issue authentication bearer token",
    description="Authenticate with email and password to receive an active Bearer token.",
)
def login_for_token(
    credentials: LoginRequest,
    db: Session = Depends(get_db),
) -> TokenResponse:
    stmt = select(User).where(User.email == credentials.email.lower().strip())
    user = db.execute(stmt).scalars().first()

    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid credentials",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="User account is disabled",
        )

    # If the user has a configured password hash, verify it.
    if user.password_hash is not None:
        if not credentials.password or not verify_password(credentials.password, user.password_hash):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid credentials",
                headers={"WWW-Authenticate": "Bearer"},
            )

    token = issue_user_token(db, user.id, expires_in_days=30)
    return TokenResponse(
        token=str(token.token),
        expires_at=token.expires_at,
    )


@router.post(
    "/register",
    response_model=UserResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Register a new user account",
    description="Create a new resident user account. Client-supplied roles are ignored; users always start as RESIDENT.",
)
def register_user(
    payload: RegisterRequest,
    full_name: str | None = None,
    display_name: str | None = None,
    db: Session = Depends(get_db),
) -> UserResponse:
    email_clean = payload.email.lower().strip()
    existing = db.execute(select(User).where(User.email == email_clean)).scalars().first()
    if existing is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A user with this email address already exists",
        )

    pw_hash = hash_password(payload.password) if payload.password else None
    resolved_full_name = payload.full_name or full_name
    resolved_display_name = payload.display_name or display_name or (resolved_full_name.split()[0] if resolved_full_name else email_clean.split("@")[0])
    user = User(
        id=str(uuid.uuid4()),
        email=email_clean,
        password_hash=pw_hash,
        role=UserRole.RESIDENT,  # Never trust client role
        full_name=resolved_full_name,
        display_name=resolved_display_name,
        is_active=True,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return UserResponse.model_validate(user)


@router.get(
    "/me",
    response_model=UserResponse,
    summary="Get current user profile",
    description="Retrieve the profile of the currently authenticated session.",
)
def get_current_user_profile(
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> UserResponse:
    user = db.get(User, str(current_user.id))
    if user is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )
    return UserResponse.model_validate(user)
