"""Synchronization endpoints for offline-first clients."""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.core.auth import CurrentUser
from app.database.session import get_db
from app.schemas.report import ChangesResponse, SyncRequest, SyncResponse
from app.services import report as report_service

router = APIRouter()


@router.post(
    "/report",
    response_model=SyncResponse,
    summary="Synchronize offline report submission",
    description="Idempotently submit an offline-created report. Retries do not overwrite review decisions or server state.",
)
def sync_report_endpoint(
    payload: SyncRequest,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> SyncResponse:
    result = report_service.sync_report(
        db,
        owner_id=current_user.id,
        sync_data=payload,
    )
    db.commit()
    return result


@router.get(
    "/changes",
    response_model=ChangesResponse,
    summary="Retrieve authorized changes since cursor",
    description="Pull reports modified after the specified timestamp. Supports deterministic pagination.",
)
def get_changes_endpoint(
    current_user: CurrentUser,
    since: Optional[datetime] = Query(
        default=None, description="ISO 8601 UTC timestamp cursor"
    ),
    limit: int = Query(default=100, ge=1, le=1000, description="Max changes to return"),
    status: Optional[str] = Query(default=None, description="Filter by status"),
    db: Session = Depends(get_db),
) -> ChangesResponse:
    reports = report_service.get_changes_since(
        db,
        user_id=current_user.id,
        user_role=current_user.role,
        since=since,
        limit=limit,
        status_filter=status,
    )
    has_more = len(reports) == limit
    next_cursor = reports[-1].updated_at if reports else None
    return ChangesResponse(
        reports=reports,
        has_more=has_more,
        next_cursor=next_cursor,
    )
