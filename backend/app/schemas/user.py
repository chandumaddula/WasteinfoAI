"""Pydantic v2 schemas for user validation and serialization."""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field


class UserBase(BaseModel):
    """Base user fields."""

    model_config = ConfigDict(
        str_strip_whitespace=True,
        from_attributes=True,
    )

    email: str = Field(
        ...,
        pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$",
        max_length=255,
        description="User's email address (unique)",
        examples=["user@example.com"]
    )

    full_name: Optional[str] = Field(
        None,
        max_length=128,
        description="User's full name",
        examples=["John Doe"]
    )

    display_name: Optional[str] = Field(
        None,
        max_length=64,
        description="User's preferred display name",
        examples=["johndoe"]
    )


class UserCreate(UserBase):
    """Schema for creating a new user (admin only)."""

    role: str = Field(
        default="RESIDENT",
        description="User role",
        examples=["RESIDENT", "REVIEWER", "ADMIN"]
    )

    institution_id: Optional[str] = Field(
        None,
        max_length=36,
        description="Optional institution UUID"
    )


class UserUpdate(BaseModel):
    """Schema for updating user profile (limited fields)."""

    model_config = ConfigDict(
        str_strip_whitespace=True,
        from_attributes=True,
    )

    full_name: Optional[str] = Field(
        None,
        max_length=128,
        description="User's full name"
    )

    display_name: Optional[str] = Field(
        None,
        max_length=64,
        description="User's preferred display name"
    )


class UserResponse(UserBase):
    """Schema for user responses (includes server-generated fields)."""

    id: str = Field(
        ...,
        description="Server-generated user UUID",
        examples=["123e4567-e89b-12d3-a456-426614174000"]
    )

    role: str = Field(
        ...,
        description="User's role",
        examples=["RESIDENT", "REVIEWER", "ADMIN"]
    )

    institution_id: Optional[str] = Field(
        None,
        description="Institution UUID if the user belongs to one"
    )

    is_active: bool = Field(
        ...,
        description="Whether the user account is active",
        examples=[True, False]
    )

    created_at: datetime = Field(
        ...,
        description="Account creation timestamp (UTC)",
        examples=["2026-10-11T10:30:00Z"]
    )

    updated_at: datetime = Field(
        ...,
        description="Last profile update timestamp (UTC)",
        examples=["2026-10-11T14:45:00Z"]
    )


class TokenResponse(BaseModel):
    """Response for token generation."""

    token: str = Field(
        ...,
        description="Bearer token for authentication",
        examples=["550e8400-e29b-41d4-a716-446655440000"]
    )

    expires_at: Optional[datetime] = Field(
        None,
        description="Token expiration timestamp (UTC) if applicable"
    )


class LoginRequest(BaseModel):
    """Request schema for authentication and token issuance."""

    email: str = Field(
        ...,
        pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$",
        max_length=255,
        description="User's email address",
        examples=["user@example.com"]
    )
    password: Optional[str] = Field(
        None,
        description="User's password (verified if user has password configured)",
        examples=["secure-password-123"]
    )


class RegisterRequest(BaseModel):
    """Request schema for self-registration following application rules."""

    model_config = ConfigDict(
        str_strip_whitespace=True,
        from_attributes=True,
    )

    email: str = Field(
        ...,
        pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$",
        max_length=255,
        description="User's email address",
        examples=["user@example.com"]
    )
    password: Optional[str] = Field(
        None,
        description="User's password",
        examples=["secure-password-123"]
    )
    full_name: Optional[str] = Field(
        None,
        max_length=128,
        description="User's full name",
        examples=["John Doe"]
    )
    display_name: Optional[str] = Field(
        None,
        max_length=64,
        description="User's preferred display name",
        examples=["johndoe"]
    )