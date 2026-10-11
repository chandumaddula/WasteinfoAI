"""Authorization dependencies and service-level ownership checks.

These dependencies are designed to be used in FastAPI route handlers via
``Depends()``. They enforce role-based access control and ownership checks
without duplicating business logic in routes.

All access is denied by default; permissions are explicitly granted.
"""
from __future__ import annotations

from typing import TYPE_CHECKING

from fastapi import Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.auth import AuthenticatedUser, CurrentUser, get_current_user
from app.database.session import get_db
from app.models.report import Report, ReportStatus
from app.models.user import UserRole

if TYPE_CHECKING:
    from app.models.user import User


def _check_role(
    user: AuthenticatedUser,
    allowed: list[UserRole],
) -> None:
    """Raise 403 if the user's role is not in ``allowed``."""
    if user.role not in allowed:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Insufficient permissions",
        )


def _check_report_ownership(
    db: Session,
    report_id: str,
    user: AuthenticatedUser,
) -> None:
    """Raise 403 if the user does not own the given report.

    Also grants access to ADMIN and REVIEWER regardless of ownership.
    """
    stmt = select(Report).where(Report.id == report_id)
    result = db.execute(stmt)
    report = result.scalars().first()
    if report is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Report not found",
        )

    # Admin and Reviewer can access any report.
    if user.role in (UserRole.ADMIN, UserRole.REVIEWER):
        return

    # Resident can only access their own reports.
    if user.role == UserRole.RESIDENT and report.owner_id != user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Not report owner",
        )

    # Institution member access (if institution_id matches).
    if user.role == UserRole.INSTITUTION and report.owner.institution_id == user.institution_id:
        return

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Not report owner",
    )


def _check_can_review(
    user: AuthenticatedUser,
) -> None:
    """Raise 403 if the user is not allowed to review reports."""
    if user.role not in (UserRole.REVIEWER, UserRole.ADMIN):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Review required",
        )


def _check_can_assign(
    user: AuthenticatedUser,
) -> None:
    """Raise 403 if the user is not allowed to assign reports."""
    if user.role not in (UserRole.ADMIN,):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Assignment required",
        )


def _check_can_update_task(
    db: Session,
    user: AuthenticatedUser,
    report_id: str,
) -> None:
    """Raise 403 if the user cannot update the task.

    SANITATION_WORKER can update reports assigned to them.
    """
    stmt = select(Report).where(Report.id == report_id)
    result = db.execute(stmt)
    report = result.scalars().first()
    if report is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Report not found",
        )

    if user.role == UserRole.SANITATION_WORKER:
        # In a full design, we would track which reports are assigned to which worker.
        # For now, allow if the report is in UNDER_REVIEW or VERIFIED state
        # (the worker has not yet claimed it or has been assigned).
        if report.status in (
            ReportStatus.UNDER_REVIEW,
            ReportStatus.VERIFIED,
        ):
            return
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Report not assigned to this worker",
        )

    # Admin can always update.
    if user.role == UserRole.ADMIN:
        return

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="Only SANITATION_WORKER or ADMIN can update this task",
    )


# ── FastAPI dependency wrappers ────────────────────────────────────────

def require_role(*allowed: UserRole):
    """FastAPI dependency that enforces a role check.

    Usage::

        @router.post("/reports", dependencies=[Depends(require_role(UserRole.ADMIN))])
    """

    def _dependency(
        user: CurrentUser,
    ) -> AuthenticatedUser:
        _check_role(user, list(allowed))
        return user

    return _dependency


def require_reviewer(
    user: CurrentUser,
) -> AuthenticatedUser:
    """FastAPI dependency that enforces REVIEWER or ADMIN role."""
    _check_role(user, [UserRole.REVIEWER, UserRole.ADMIN])
    return user


def require_admin(
    user: CurrentUser,
) -> AuthenticatedUser:
    """FastAPI dependency that enforces ADMIN role."""
    _check_role(user, [UserRole.ADMIN])
    return user


def check_report_ownership(
    report_id: str,
    user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    """FastAPI dependency that checks ownership of a specific report.

    Raises 404 if the report does not exist, 403 if the user is not the owner
    (unless admin/reviewer).

    Usage::

        @router.get("/reports/{report_id}")
        async def get_report(
            report_id: str,
            _: None = Depends(check_report_ownership),
        ):
            ...
    """
    _check_report_ownership(db, report_id, user)


def require_review_access(
    user: CurrentUser,
) -> None:
    """FastAPI dependency that reviewer access is required.

    Usage::

        @router.post("/reports/{report_id}/review")
        async def review_report(
            _: None = Depends(require_review_access),
        ):
            ...
    """
    _check_can_review(user)


def require_assign_access(
    user: CurrentUser,
) -> None:
    """FastAPI dependency that admin access is required for assignment."""
    _check_can_assign(user)


def require_task_update_access(
    report_id: str,
    user: CurrentUser,
    db: Session = Depends(get_db),
) -> None:
    """FastAPI dependency that task update access is required."""
    _check_can_update_task(db, user, report_id)