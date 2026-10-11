"""Comprehensive unit tests for ReportService business logic and transactions."""
import uuid
import pytest
from sqlalchemy import select

from app.core.errors import AppError
from app.core.validation import TransitionNotAllowed
from app.models.audit_event import AuditEvent
from app.models.report import Report, ReportStatus
from app.models.user import User, UserRole
from app.schemas.report import (
    ReportCreate,
    ReportUpdate,
    ReviewDecision,
    ReviewRequest,
    SyncRequest,
)
from app.services import report as report_service


@pytest.fixture()
def resident_user(db_session):
    u = User(
        id=str(uuid.uuid4()),
        email="resident@example.com",
        role=UserRole.RESIDENT,
        display_name="Resident One",
    )
    db_session.add(u)
    db_session.commit()
    return u


@pytest.fixture()
def other_resident(db_session):
    u = User(
        id=str(uuid.uuid4()),
        email="other@example.com",
        role=UserRole.RESIDENT,
        display_name="Resident Two",
    )
    db_session.add(u)
    db_session.commit()
    return u


@pytest.fixture()
def reviewer_user(db_session):
    u = User(
        id=str(uuid.uuid4()),
        email="reviewer@example.com",
        role=UserRole.REVIEWER,
        display_name="Reviewer One",
    )
    db_session.add(u)
    db_session.commit()
    return u


@pytest.fixture()
def admin_user(db_session):
    u = User(
        id=str(uuid.uuid4()),
        email="admin@example.com",
        role=UserRole.ADMIN,
        display_name="Admin One",
    )
    db_session.add(u)
    db_session.commit()
    return u


def test_create_report_success(db_session, resident_user):
    payload = ReportCreate(
        waste_category="plastic",
        description="Bottles on the curb",
        latitude=12.9716,
        longitude=77.5946,
    )
    report = report_service.create_report(
        db_session,
        owner_id=resident_user.id,
        create_data=payload,
    )
    db_session.commit()

    assert report.id is not None
    assert report.owner_id == resident_user.id
    assert report.waste_category == "plastic"
    assert report.status == ReportStatus.UNDER_REVIEW
    assert report.version == 0
    assert report.latitude == 12.9716
    assert report.longitude == 77.5946


def test_create_report_idempotency_with_client_uuid(db_session, resident_user):
    client_id = str(uuid.uuid4())
    payload = ReportCreate(
        waste_category="glass",
        address_line="100 Market St",
        city="Bengaluru",
        client_uuid=client_id,
    )

    # First attempt: creates the report
    report1 = report_service.create_report(
        db_session,
        owner_id=resident_user.id,
        create_data=payload,
        client_uuid=client_id,
    )
    db_session.commit()
    report1_id = report1.id

    # Second attempt with same client_uuid: returns existing report
    report2 = report_service.create_report(
        db_session,
        owner_id=resident_user.id,
        create_data=payload,
        client_uuid=client_id,
    )
    assert report2.id == report1_id


def test_different_owners_can_use_same_client_uuid(db_session, resident_user, other_resident):
    shared_client_id = str(uuid.uuid4())
    payload = ReportCreate(
        waste_category="metal",
        address_line="500 Park Ave",
        city="Bengaluru",
        client_uuid=shared_client_id,
    )

    r1 = report_service.create_report(
        db_session,
        owner_id=resident_user.id,
        create_data=payload,
        client_uuid=shared_client_id,
    )
    db_session.commit()

    r2 = report_service.create_report(
        db_session,
        owner_id=other_resident.id,
        create_data=payload,
        client_uuid=shared_client_id,
    )
    db_session.commit()

    assert r1.id != r2.id
    assert r1.owner_id == resident_user.id
    assert r2.owner_id == other_resident.id


def test_update_report_and_optimistic_concurrency(db_session, resident_user, other_resident):
    payload = ReportCreate(
        waste_category="paper",
        address_line="Old Address",
        city="Bengaluru",
    )
    report = report_service.create_report(
        db_session,
        owner_id=resident_user.id,
        create_data=payload,
    )
    db_session.commit()
    assert report.version == 0

    # Non-owner cannot update
    with pytest.raises(AppError) as exc:
        report_service.update_report(
            db_session,
            report_id=report.id,
            update_data=ReportUpdate(description="Hacked"),
            user_id=other_resident.id,
            user_role=other_resident.role,
        )
    assert exc.value.status_code == 403

    # Owner updates with correct expected_version
    updated = report_service.update_report(
        db_session,
        report_id=report.id,
        update_data=ReportUpdate(description="Updated by owner"),
        user_id=resident_user.id,
        user_role=resident_user.role,
        expected_version=0,
    )
    db_session.commit()
    assert updated.version == 1
    assert updated.description == "Updated by owner"

    # Stale update with old expected_version fails
    with pytest.raises(ValueError) as exc:
        report_service.update_report(
            db_session,
            report_id=report.id,
            update_data=ReportUpdate(description="Stale update"),
            user_id=resident_user.id,
            user_role=resident_user.role,
            expected_version=0,  # current version is 1!
        )
    assert "modified by another process" in str(exc.value)


def test_review_decisions_and_audit_logging(db_session, resident_user, reviewer_user):
    report = report_service.create_report(
        db_session,
        owner_id=resident_user.id,
        create_data=ReportCreate(
            waste_category="organic",
            address_line="Veg Market",
            city="Bengaluru",
        ),
    )
    db_session.commit()
    report_id = report.id

    # Non-reviewer cannot review
    with pytest.raises(AppError) as exc:
        report_service.submit_review_decision(
            db_session,
            report_id=report_id,
            review_data=ReviewRequest(decision=ReviewDecision.VERIFY, reason="Looks fine"),
            reviewer_id=resident_user.id,
        )
    assert exc.value.status_code == 403

    # Reviewer verifies the report
    verified_report = report_service.submit_review_decision(
        db_session,
        report_id=report_id,
        review_data=ReviewRequest(decision=ReviewDecision.VERIFY, reason="Verified on site"),
        reviewer_id=reviewer_user.id,
    )
    db_session.commit()

    assert verified_report.status == ReportStatus.VERIFIED
    assert verified_report.version == 1

    # Check audit event was written
    events = db_session.execute(
        select(AuditEvent).where(AuditEvent.subject_id == report_id)
    ).scalars().all()
    assert len(events) == 1
    assert events[0].event_type == "review_decision"
    assert events[0].actor_id == reviewer_user.id
    assert events[0].payload["decision"] == "VERIFY"
    assert events[0].payload["previous_status"] == "UNDER_REVIEW"
    assert events[0].payload["new_status"] == "VERIFIED"


def test_offline_sync_idempotency_and_preserves_reviews(db_session, resident_user, reviewer_user):
    client_uuid = str(uuid.uuid4())
    sync_req = SyncRequest(
        waste_category="Battery",
        address_line="Hardware Store",
        city="Bengaluru",
        client_uuid=client_uuid,
    )

    # 1. Sync submission: newly created
    resp1 = report_service.sync_report(
        db_session,
        owner_id=resident_user.id,
        sync_data=sync_req,
    )
    db_session.commit()
    assert resp1.created is True
    report_id = resp1.report.id

    # 2. Reviewer reviews and verifies the report on the server
    report_service.submit_review_decision(
        db_session,
        report_id=report_id,
        review_data=ReviewRequest(decision=ReviewDecision.VERIFY, reason="Confirmed"),
        reviewer_id=reviewer_user.id,
    )
    db_session.commit()

    # 3. Client retries sync with same client_uuid
    resp2 = report_service.sync_report(
        db_session,
        owner_id=resident_user.id,
        sync_data=sync_req,
    )
    db_session.commit()

    assert resp2.created is False
    assert resp2.report.id == report_id
    # Server verification decision was preserved, not overwritten back to UNDER_REVIEW!
    assert resp2.report.status == "VERIFIED"


def test_get_changes_since_and_access_control(db_session, resident_user, other_resident, reviewer_user):
    # Resident 1 creates report
    r1 = report_service.create_report(
        db_session,
        owner_id=resident_user.id,
        create_data=ReportCreate(waste_category="cardboard", address_line="R1 Home", city="Bengaluru"),
    )
    # Resident 2 creates report
    r2 = report_service.create_report(
        db_session,
        owner_id=other_resident.id,
        create_data=ReportCreate(waste_category="metal", address_line="R2 Home", city="Bengaluru"),
    )
    db_session.commit()

    # Resident 1 only sees their own report
    changes_r1 = report_service.get_changes_since(
        db_session,
        user_id=resident_user.id,
        user_role=resident_user.role,
    )
    assert len(changes_r1) == 1
    assert changes_r1[0].id == r1.id

    # Reviewer sees all reports
    changes_rev = report_service.get_changes_since(
        db_session,
        user_id=reviewer_user.id,
        user_role=reviewer_user.role,
    )
    assert len(changes_rev) >= 2
    ids = {c.id for c in changes_rev}
    assert r1.id in ids and r2.id in ids
