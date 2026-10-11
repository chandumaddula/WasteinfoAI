"""Report model and workflow status.

A report represents a submitted waste observation. Its workflow status tracks the
review lifecycle independently of synchronization state.
"""
from __future__ import annotations

import datetime
import enum
import uuid

from sqlalchemy import Boolean, Float, ForeignKey, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.base import Base


class ReportStatus(str, enum.Enum):
    """Valid workflow status transitions for a report."""

    DRAFT = "DRAFT"
    UNDER_REVIEW = "UNDER_REVIEW"
    VERIFIED = "VERIFIED"
    ASSIGNED = "ASSIGNED"
    RESOLVED = "RESOLVED"
    REJECTED = "REJECTED"
    DUPLICATE = "DUPLICATE"


class Report(Base):
    """A waste identification report submitted by a user or client.

    - Report ID is server-generated UUID.
    - Client UUID enables idempotent offline retries.
    - Owner is derived from the authenticated session; client-provided user_id is never trusted.
    - Waste category must be from the canonical 17-class list.
    - Location and image reference are validated at the schema/service layer.
    - Status transitions follow the defined workflow (see ``ReportStatus``).
    """

    __tablename__ = "reports"

    id: Mapped[uuid.UUID] = mapped_column(
        String(36), primary_key=True, default=uuid.uuid4
    )
    # Stable client-generated UUID used for idempotent retries.
    # Scope: (owner_id) so different owners can reuse the same client UUID.
    client_uuid: Mapped[uuid.UUID | None] = mapped_column(
        String(36), nullable=True, unique=False, index=True
    )
    owner_id: Mapped[uuid.UUID] = mapped_column(
        String(36), ForeignKey("users.id"), nullable=False, index=True
    )
    waste_category: Mapped[str] = mapped_column(
        String(50), nullable=False, index=True
    )
    description: Mapped[str | None] = mapped_column(
        Text, nullable=True
    )
    latitude: Mapped[float | None] = mapped_column(
        Float, nullable=True
    )
    longitude: Mapped[float | None] = mapped_column(
        Float, nullable=True
    )
    # Manual location fields (when GPS is unavailable or not used).
    address_line: Mapped[str | None] = mapped_column(
        Text, nullable=True
    )
    city: Mapped[str | None] = mapped_column(String(128), nullable=True)
    region: Mapped[str | None] = mapped_column(String(128), nullable=True)
    country: Mapped[str | None] = mapped_column(String(128), nullable=True)
    # Image / evidence reference stored as a opaque reference; the storage layer
    # validates its existence when needed.
    image_reference: Mapped[str | None] = mapped_column(
        String(512), nullable=True
    )
    # Server-generated timestamps; clients must never overwrite.
    created_at: Mapped[datetime.datetime] = mapped_column(
        default=datetime.datetime.now(datetime.timezone.utc), nullable=False
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        default=datetime.datetime.now(datetime.timezone.utc),
        onupdate=datetime.datetime.now(datetime.timezone.utc),
        nullable=False,
    )
    # Review workflow status. New reports start at ``UNDER_REVIEW``.
    status: Mapped[ReportStatus] = mapped_column(
        String(20), nullable=False, default=ReportStatus.UNDER_REVIEW, index=True
    )
    # Optimistic concurrency version. Incremented on every successful review
    # or update that changes state. Used by the sync layer to detect stale updates.
    version: Mapped[int] = mapped_column(
        default=0, nullable=False
    )
    # Synchronization metadata.
    sync_state: Mapped[str | None] = mapped_column(
        String(20), nullable=True, default=None
    )
    sync_received_at: Mapped[datetime.datetime | None] = mapped_column(
        default=None, nullable=True
    )
    sync_last_attempt_at: Mapped[datetime.datetime | None] = mapped_column(
        default=None, nullable=True
    )
    sync_retry_count: Mapped[int] = mapped_column(
        default=0, nullable=False
    )

    __table_args__ = (
        UniqueConstraint("owner_id", "client_uuid", name="uq_reports_owner_id_client_uuid"),
    )

    # Relationships
    owner: Mapped["User"] = relationship(
        "User", back_populates="reports", lazy="joined"
    )

    def __repr__(self) -> str:
        return (
            f"<Report(id={self.id!r} owner={self.owner_id!r} "
            f"category={self.waste_category!r} status={self.status.value!r})>"
        )
