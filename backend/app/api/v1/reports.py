"""Report endpoints for creating, listing, inspecting, updating, and reviewing waste reports."""
from __future__ import annotations

from typing import Annotated, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.core.auth import AuthenticatedUser, CurrentUser
from app.core.permissions import require_reviewer
from app.database.session import get_db
from app.models.user import UserRole
from app.schemas.report import (
    ReportCreate,
    ReportListResponse,
    ReportResponse,
    ReportUpdate,
    ReviewRequest,
)
from app.services import report as report_service

router = APIRouter()


@router.post(
    "",
    response_model=ReportResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create waste report",
    description="Submit a new waste identification report. Idempotency is supported via client_uuid.",
)
def create_report_endpoint(
    payload: ReportCreate,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ReportResponse:
    report = report_service.create_report(
        db,
        owner_id=current_user.id,
        create_data=payload,
        client_uuid=payload.client_uuid,
    )
    db.commit()
    db.refresh(report)
    return ReportResponse.model_validate(report)


@router.get(
    "",
    response_model=ReportListResponse,
    summary="List waste reports",
    description="Retrieve reports visible to the authenticated user with deterministic pagination.",
)
def list_reports_endpoint(
    current_user: CurrentUser,
    limit: int = Query(default=50, ge=1, le=100, description="Page limit"),
    offset: int = Query(default=0, ge=0, description="Page offset"),
    status: Optional[str] = Query(default=None, description="Filter by ReportStatus"),
    db: Session = Depends(get_db),
) -> ReportListResponse:
    reports, total = report_service.list_reports(
        db,
        user_id=current_user.id,
        user_role=current_user.role,
        limit=limit,
        offset=offset,
        status_filter=status,
    )
    return ReportListResponse(
        reports=[ReportResponse.model_validate(r) for r in reports],
        total=total,
        limit=limit,
        offset=offset,
        has_more=(offset + limit < total),
    )


@router.get(
    "/{report_id}",
    response_model=ReportResponse,
    summary="Get report by ID",
    description="Retrieve a single report by its ID, enforcing role-based visibility and ownership.",
)
def get_report_endpoint(
    report_id: str,
    current_user: CurrentUser,
    db: Session = Depends(get_db),
) -> ReportResponse:
    try:
        report = report_service.get_report(
            db,
            report_id=report_id,
            owner_id=current_user.id if current_user.role == UserRole.RESIDENT else None,
            user_role=current_user.role,
        )
    except LookupError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Report not found",
        )
    return ReportResponse.model_validate(report)


@router.patch(
    "/{report_id}",
    response_model=ReportResponse,
    summary="Update report details",
    description="Update description or location. Supports optimistic concurrency via expected_version.",
)
def update_report_endpoint(
    report_id: str,
    payload: ReportUpdate,
    current_user: CurrentUser,
    expected_version: Optional[int] = Query(
        default=None, description="Expected version for optimistic locking"
    ),
    db: Session = Depends(get_db),
) -> ReportResponse:
    try:
        report = report_service.update_report(
            db,
            report_id=report_id,
            update_data=payload,
            user_id=current_user.id,
            user_role=current_user.role,
            expected_version=expected_version,
        )
        db.commit()
        db.refresh(report)
    except LookupError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Report not found",
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=str(e),
        )
    return ReportResponse.model_validate(report)


@router.post(
    "/{report_id}/review",
    response_model=ReportResponse,
    summary="Submit review decision",
    description="Authorized reviewers or admins submit review verdicts (VERIFY, REJECT, ASSIGN, MARK_AS_DUPLICATE).",
)
def submit_review_endpoint(
    report_id: str,
    payload: ReviewRequest,
    reviewer: Annotated[AuthenticatedUser, Depends(require_reviewer)],
    db: Session = Depends(get_db),
) -> ReportResponse:
    try:
        report = report_service.submit_review_decision(
            db,
            report_id=report_id,
            review_data=payload,
            reviewer_id=reviewer.id,
        )
        db.commit()
        db.refresh(report)
    except LookupError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Report not found",
        )
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=str(e),
        )
    return ReportResponse.model_validate(report)
