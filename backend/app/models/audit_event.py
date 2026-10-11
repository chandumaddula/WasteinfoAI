"""Audit event model for tracking important state transitions.

Audit events track reviewer decisions, duplicate markings, and other
administrative actions. They provide an immutable audit trail for
important operations.
"""
from __future__ import annotations

import datetime
import uuid

from sqlalchemy import JSON, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from app.database.base import Base


class AuditEvent(Base):
    """Immutable record of important application events.

    Used to track reviewer decisions, duplicate markings, and administrative actions.
    The ``event_type`` field indicates the category of event.
    """

    __tablename__ = "audit_events"

    id: Mapped[uuid.UUID] = mapped_column(String(36), primary_key=True, default=lambda: str(uuid.uuid4()))

    # The subject resource (e.g., report) that the event relates to.
    subject_type: Mapped[str] = mapped_column(String(50), nullable=False, index=True)
    subject_id: Mapped[uuid.UUID] = mapped_column(String(36), nullable=False, index=True)

    # The event category (e.g., "report_review", "report_duplicate").
    event_type: Mapped[str] = mapped_column(String(50), nullable=False, index=True)

    # The actor who performed the action (user ID).
    actor_id: Mapped[uuid.UUID] = mapped_column(String(36), nullable=False, index=True)

    # The payload describing the action (e.g., target status, reason).
    payload: Mapped[dict | None] = mapped_column(
        JSON,  # Store as JSON object
        nullable=True
    )

    # Server-generated timestamp.
    created_at: Mapped[datetime.datetime] = mapped_column(
        default=datetime.datetime.now(datetime.timezone.utc), nullable=False, index=True
    )

    def __repr__(self) -> str:
        return (
            f"<AuditEvent(id={self.id!r} type={self.event_type!r} "
            f"actor={self.actor_id!r})>"
        )
