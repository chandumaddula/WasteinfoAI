"""Comprehensive unit tests for domain validation rules and state transitions."""
from datetime import datetime, timezone, timedelta
import pytest

from app.core.validation import (
    CategoryError,
    IdentifierError,
    LocationError,
    RoleError,
    StatusError,
    TimestampError,
    TransitionNotAllowed,
    get_valid_categories,
    is_terminal_status,
    is_valid_role,
    is_valid_waste_category,
    parse_utc_timestamp,
    validate_latitude,
    validate_location,
    validate_longitude,
    validate_non_future_server_timestamp,
    validate_role,
    validate_status_transition,
    validate_uuid,
    validate_waste_category,
)
from app.models.report import ReportStatus
from app.models.user import UserRole
from app.schemas.report import WASTE_CATEGORIES


def test_all_17_canonical_categories_are_recognized():
    assert len(WASTE_CATEGORIES) == 17
    categories = get_valid_categories()
    assert len(categories) == 17

    expected = {
        "Battery", "cardboard", "glass", "Keyboard", "metal",
        "Microwave", "Mobile", "Mouse", "organic", "paper",
        "PCB", "plastic", "Player", "Printer", "Television",
        "trash", "Washing Machine",
    }
    assert set(categories) == expected

    for cat in expected:
        assert is_valid_waste_category(cat) is True
        assert is_valid_waste_category(cat.lower()) is True
        assert is_valid_waste_category(cat.upper()) is True
        # Canonical casing is returned
        assert validate_waste_category(cat.lower()) == cat


def test_invalid_waste_categories_are_rejected():
    assert is_valid_waste_category("nuclear") is False
    assert is_valid_waste_category("unknown_junk") is False
    assert is_valid_waste_category(None) is False

    with pytest.raises(CategoryError) as exc:
        validate_waste_category("space_debris")
    assert exc.value.status_code == 422
    assert "Unknown waste category" in exc.value.message

    with pytest.raises(CategoryError):
        validate_waste_category("")

    with pytest.raises(CategoryError):
        validate_waste_category(None)


def test_latitude_and_longitude_boundaries():
    assert validate_latitude(0.0) == 0.0
    assert validate_latitude(90.0) == 90.0
    assert validate_latitude(-90.0) == -90.0
    assert validate_latitude(None) is None

    with pytest.raises(LocationError):
        validate_latitude(90.1)
    with pytest.raises(LocationError):
        validate_latitude(-90.1)
    with pytest.raises(LocationError):
        validate_latitude("not_a_number")

    assert validate_longitude(0.0) == 0.0
    assert validate_longitude(180.0) == 180.0
    assert validate_longitude(-180.0) == -180.0
    assert validate_longitude(None) is None

    with pytest.raises(LocationError):
        validate_longitude(180.1)
    with pytest.raises(LocationError):
        validate_longitude(-180.1)
    with pytest.raises(LocationError):
        validate_longitude("bad_longitude")


def test_location_payload_validation():
    # Valid GPS
    gps_loc = validate_location({"latitude": 12.9716, "longitude": 77.5946})
    assert gps_loc["latitude"] == 12.9716
    assert gps_loc["longitude"] == 77.5946

    # Valid manual
    manual_loc = validate_location({"address_line": "123 Main St", "city": "Bengaluru"})
    assert manual_loc["address_line"] == "123 Main St"
    assert manual_loc["city"] == "Bengaluru"

    # Incomplete GPS (latitude without longitude)
    with pytest.raises(LocationError) as exc:
        validate_location({"latitude": 12.9716})
    assert "Both latitude and longitude must be provided together" in exc.value.message

    # Empty payload (neither GPS nor manual)
    with pytest.raises(LocationError) as exc:
        validate_location({})
    assert "Location is required" in exc.value.message


def test_status_transition_rules():
    # Valid forward transitions
    validate_status_transition(ReportStatus.DRAFT, ReportStatus.UNDER_REVIEW)
    validate_status_transition(ReportStatus.UNDER_REVIEW, ReportStatus.VERIFIED)
    validate_status_transition(ReportStatus.UNDER_REVIEW, ReportStatus.REJECTED)
    validate_status_transition(ReportStatus.UNDER_REVIEW, ReportStatus.DUPLICATE)
    validate_status_transition(ReportStatus.VERIFIED, ReportStatus.ASSIGNED)
    validate_status_transition(ReportStatus.ASSIGNED, ReportStatus.RESOLVED)

    # Self-transition is allowed
    validate_status_transition(ReportStatus.UNDER_REVIEW, ReportStatus.UNDER_REVIEW)

    # Disallowed transitions raise TransitionNotAllowed (HTTP 409)
    with pytest.raises(TransitionNotAllowed) as exc:
        validate_status_transition(ReportStatus.RESOLVED, ReportStatus.UNDER_REVIEW)
    assert exc.value.status_code == 409

    with pytest.raises(TransitionNotAllowed):
        validate_status_transition(ReportStatus.REJECTED, ReportStatus.VERIFIED)

    with pytest.raises(TransitionNotAllowed):
        validate_status_transition(ReportStatus.DUPLICATE, ReportStatus.ASSIGNED)


def test_terminal_statuses():
    assert is_terminal_status(ReportStatus.RESOLVED) is True
    assert is_terminal_status(ReportStatus.REJECTED) is True
    assert is_terminal_status(ReportStatus.DUPLICATE) is True
    assert is_terminal_status(ReportStatus.UNDER_REVIEW) is False
    assert is_terminal_status(ReportStatus.VERIFIED) is False


def test_uuid_validation():
    valid = "550E8400-E29B-41D4-A716-446655440000"
    assert validate_uuid(valid) == valid.lower()
    assert validate_uuid(None) is None

    with pytest.raises(IdentifierError) as exc:
        validate_uuid("invalid-uuid-string")
    assert exc.value.status_code == 422


def test_role_validation():
    assert is_valid_role("RESIDENT") is True
    assert is_valid_role("ADMIN") is True
    assert is_valid_role("INVALID_ROLE") is False

    assert validate_role("RESIDENT") == UserRole.RESIDENT
    assert validate_role(UserRole.REVIEWER) == UserRole.REVIEWER

    # Reserved ADMIN role cannot be assigned without allow_reserved=True
    with pytest.raises(RoleError) as exc:
        validate_role("ADMIN", allow_reserved=False)
    assert exc.value.status_code == 422
    assert "Admin role cannot be assigned" in exc.value.message

    assert validate_role("ADMIN", allow_reserved=True) == UserRole.ADMIN


def test_timestamp_validation():
    now = datetime.now(timezone.utc)
    assert parse_utc_timestamp(now) == now
    assert parse_utc_timestamp(None) is None

    parsed = parse_utc_timestamp("2026-10-11T10:00:00Z")
    assert parsed.tzinfo == timezone.utc

    with pytest.raises(TimestampError):
        parse_utc_timestamp("invalid-date")

    # Non-future validation
    past = now - timedelta(hours=1)
    assert validate_non_future_server_timestamp(past) == past

    future = now + timedelta(hours=2)
    with pytest.raises(TimestampError):
        validate_non_future_server_timestamp(future)
