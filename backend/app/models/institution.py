"""Optional organizational affiliation for users.

When the feature is enabled, institution members can see reports created by other
members of the same institution. Ownership checks in the service layer remain the
authority; this table is only consulted when explicitly requested.
"""
from __future__ import annotations

import datetime
import uuid

from sqlalchemy import Boolean, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.base import Base


class Institution(Base):
    """Optional organizational affiliation for users.

    When the feature is enabled, institution members can see reports created by other
    members of the same institution. Ownership checks in the service layer remain the
    authority; this table is only consulted when explicitly requested.
    """

    __tablename__ = "institutions"

    id: Mapped[uuid.UUID] = mapped_column(String(36), primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(128), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime.datetime] = mapped_column(
        default=datetime.datetime.now(datetime.timezone.utc), nullable=False
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        default=datetime.datetime.now(datetime.timezone.utc),
        onupdate=datetime.datetime.now(datetime.timezone.utc),
        nullable=False,
    )

    # Many-to-one user membership: each member belongs to at most one institution.
    members: Mapped[list["User"]] = relationship(
        "User", back_populates="institution", lazy="selectin"
    )

    def __repr__(self) -> str:
        return f"<Institution(id={self.id!r} name={self.name!r})>"

