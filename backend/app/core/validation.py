"""Reusable validation logic for business rules.

This module contains domain-specific validation functions that are used by the
service layer. Validation raises structured errors (``AppError`` with a specific
code) rather than leaking stack traces or database internals.
"""
from __future__ import annotations

import re
import uuid
from datetime import datetime, timezone
from typing import Any

from app.core.errors import AppError
from app.models.report import ReportStatus
from app.models.user import UserRole
from app.schemas.report import WASTE_CATEGORIES


# ──────────────────────────────────────────────────────────────────────────
# Waste category validation
# ──────────────────────────────────────────────────────────────────────────

class CategoryError(AppError):
    """Raised when a waste category is invalid."""

    status_code = 422
    code = "invalid_waste_category"


def is_valid_waste_category(category: Any) -> bool:
    """Check whether a value is a valid waste category.

    Categories are compared case-insensitively against the canonical 17-class
    label set derived from the trained ML model.
    """
    if category is None:
        return False
    raw = category.value if hasattr(category, "value") else str(category)
    return str(raw).strip().lower() in {c.lower() for c in WASTE_CATEGORIES}


def validate_waste_category(category: Any) -> str:
    """Normalize a category against the canonical label set.

    Unknown categories are rejected with HTTP 422; no silent conversion is
    performed.

    Returns the canonical label (original capitalization from the ML label set)
    when the value is a valid category.
    """
    if category is None:
        raise CategoryError(
            "A waste category must be provided. The category must be one of the "
            "17 classes trained in the model."
        )

    raw = category.value if hasattr(category, "value") else str(category)
    normalized = str(raw).strip()
    if normalized == "":
        raise CategoryError(
            "A waste category must be provided."
        )

    for canonical in WASTE_CATEGORIES:
        if canonical.lower() == normalized.lower():
            return canonical

    raise CategoryError(
        f"Unknown waste category '{category}'. The supported categories are: "
        f"{', '.join(sorted(WASTE_CATEGORIES, key=str.lower))}"
    )


def get_valid_categories() -> list[str]:
    """Return the canonical list of valid waste categories."""
    return sorted(WASTE_CATEGORIES, key=str.lower)


# ──────────────────────────────────────────────────────────────────────────
# Location validation
# ──────────────────────────────────────────────────────────────────────────

class LocationError(AppError):
    """Raised when location data is invalid."""

    status_code = 422
    code = "invalid_location"


def validate_latitude(value: Any, field: str = "latitude") -> float | None:
    """Validate a latitude value (must be -90 to 90)."""
    if value is None:
        return None
    try:
        lat = float(value)
    except (ValueError, TypeError):
        raise LocationError(f"{field} must be a number between -90 and 90", details=[{"field": field}])
    if lat < -90.0 or lat > 90.0:
        raise LocationError(
            f"{field} must be between -90 and 90 degrees",
            details=[{"field": field, "value": value}]
        )
    return lat


def validate_longitude(value: Any, field: str = "longitude") -> float | None:
    """Validate a longitude value (must be -180 to 180)."""
    if value is None:
        return None
    try:
        lon = float(value)
    except (ValueError, TypeError):
        raise LocationError(
            f"{field} must be a number between -180 and 180",
            details=[{"field": field}]
        )
    if lon < -180.0 or lon > 180.0:
        raise LocationError(
            f"{field} must be between -180 and 180 degrees",
            details=[{"field": field, "value": value}]
        )
    return lon


def validate_location(fields: dict[str, Any]) -> dict[str, Any]:
    """Validate and normalize a location payload.

    Rules:
    - GPS coordinates are validated within their allowed ranges.
    - ``latitude`` and ``longitude`` are required together; one without the
      other is rejected.
    - If no GPS coordinates are given, at least one manual location field must
      be provided.
    - ``None`` values for manual fields are coerced to ``None``; blank strings
      are coerced to ``None``.
    - The function never invents coordinates for missing GPS data.

    Returns the normalized field dict.
    """
    lat = validate_latitude(fields.get("latitude"), "latitude")
    lon = validate_longitude(fields.get("longitude"), "longitude")

    # Manual location fields are normalized.
    address_line = fields.get("address_line")
    if isinstance(address_line, str) and address_line.strip() == "":
        address_line = None
    city = fields.get("city")
    if isinstance(city, str) and city.strip() == "":
        city = None
    region = fields.get("region")
    if isinstance(region, str) and region.strip() == "":
        region = None
    country = fields.get("country")
    if isinstance(country, str) and country.strip() == "":
        country = None

    has_gps = lat is not None or lon is not None
    if has_gps and (lat is None or lon is None):
        raise LocationError(
            "Both latitude and longitude must be provided together",
            details=[{"field": "latitude"}, {"field": "longitude"}]
        )

    has_manual = any(v is not None for v in (address_line, city, region, country))

    if not has_gps and not has_manual:
        raise LocationError(
            "Location is required. Provide GPS coordinates (latitude and longitude) "
            "or at least one manual location field (address_line, city, region, country)"
        )

    return {
        "latitude": lat,
        "longitude": lon,
        "address_line": address_line,
        "city": city,
        "region": region,
        "country": country,
    }


# ──────────────────────────────────────────────────────────────────────────
# Text field validation
# ───────── ───────────────────────────────────────────────────────────────

class TextValidationError(AppError):
    """Raised when a text field exceeds limits or is empty."""

    status_code = 422
    code = "text_validation_error"


def validate_text_field(
    value: Any,
    max_length: int | None = None,
    min_length: int | None = None,
    allow_empty: bool = False,
    field_name: str = "value",
) -> str | None:
    """Validate a text field with length constraints.

    - ``None`` is returned as ``None`` (never coerced to an empty string).
    - Blank strings are normalized to ``None`` unless ``allow_empty=True``.
    - Values exceeding ``max_length`` are rejected with HTTP 422 (never truncated).
    - Empty required values are rejected when ``allow_empty=False``.

    Returns the normalized string or ``None``.
    """
    if value is None:
        return None

    if not isinstance(value, str):
        raise TextValidationError(
            f"{field_name} must be a string",
            details=[{"field": field_name, "type": type(value).__name__}]
        )

    stripped = value.strip()

    if stripped == "" and not allow_empty:
        if allow_empty is False:
            return None  # Empty -> None unless explicitly allowed
        return ""

    if max_length and len(stripped) > max_length:
        raise TextValidationError(
            f"{field_name} exceeds the maximum length of {max_length} characters",
            details=[{"field": field_name, "length": len(stripped), "max_length": max_length}]
        )

    if min_length and len(stripped) < min_length:
        raise TextValidationError(
            f"{field_name} is shorter than the minimum length of {min_length} characters",
            details=[{"field": field_name, "length": len(stripped), "min_length": min_length}]
        )

    return stripped


def validate_required_text_field(value: Any, field_name: str = "value") -> str:
    """Validate that a required text field is a non-empty string."""
    result = validate_text_field(value, field_name=field_name)
    if result is None:
        raise TextValidationError(
            f"{field_name} is required",
            details=[{"field": field_name}]
        )
    return result


# ──────────────────────────────────────────────────────────────────────────
# Image / evidence reference validation
# ──────────────────────────────────────────────────────────────────────────

class ImageReferenceError(AppError):
    """Raised when an image reference is invalid."""

    status_code = 422
    code = "invalid_image_reference"


# Permitted image reference schemes: opaque string reference validated by
# the storage layer at read time. We do not trust client-provided paths or
# claim existence of a referenced image.
VALID_IMAGE_REF_SCHEMES = {"s3", "gs", "https", "http", "local", None}
MAX_IMAGE_REF_LENGTH = 512
IMAGE_REF_PATTERN = re.compile(r"^" + r"[A-Za-z0-9._/-]{1}" + r"[\w./-]{0,510}$")


def validate_image_reference(value: Any) -> str | None:
    r"""Validate an image/evidence reference.

    Rules:
    - ``None`` is acceptable (no image supplied).
    - References must be non-empty strings not exceeding the maximum length.
    - Arbitrary filesystem paths (e.g. C:\Windows\...) or raw binary blobs are
      rejected.
    - The reference is validated for format only; existence is checked by the
      storage layer when the image is accessed.
    - No truncation is performed.

    Returns the normalized reference or ``None``.
    """
    if value is None:
        return None

    if not isinstance(value, str):
        raise ImageReferenceError(
            "image_reference must be a string",
            details=[{"field": "image_reference", "type": type(value).__name__}]
        )

    # Reject absolute filesystem paths and suspicious patterns.
    if re.search(r"^[A-Za-z]:[\\/]|[\\]|[/]etc[/]|[/]var[/]", value.strip()):
        raise ImageReferenceError(
            "image_reference must be an opaque reference, not a filesystem path",
            details=[{"field": "image_reference", "value": value}]
        )

    # Enforce a maximum length.
    if len(value) > MAX_IMAGE_REF_LENGTH:
        raise ImageReferenceError(
            f"image_reference exceeds the maximum length of {MAX_IMAGE_REF_LENGTH} characters",
            details=[{"field": "image_reference", "length": len(value), "max_length": MAX_IMAGE_REF_LENGTH}]
        )

    # Basic sanity check for the reference format.
    stripped = value.strip()
    if stripped == "" or not IMAGE_REF_PATTERN.match(stripped):
        raise ImageReferenceError(
            "image_reference must be a valid opaque reference string",
            details=[{"field": "image_reference", "value": value}]
        )

    return stripped


# ──────────────────────────────────────────────────────────────────────────
# Workflow status validation
# ───────── ───────────────────────────────────────────────────────────────

class StatusError(AppError):
    """Raised when a workflow status is invalid or a transition is illegal."""

    status_code = 422
    code = "invalid_status"


class TransitionNotAllowed(AppError):
    """Raised when a status transition is not permitted by the workflow."""

    status_code = 409
    code = "transition_not_allowed"


# Valid transitions: (from_status, [allowed next statuses])
VALID_TRANSITIONS: dict[ReportStatus, set[ReportStatus]] = {
    ReportStatus.DRAFT: {
        ReportStatus.UNDER_REVIEW,
    },
    ReportStatus.UNDER_REVIEW: {
        ReportStatus.VERIFIED,
        ReportStatus.REJECTED,
        ReportStatus.DUPLICATE,
        ReportStatus.ASSIGNED,
    },
    ReportStatus.VERIFIED: {
        ReportStatus.ASSIGNED,
        ReportStatus.REJECTED,
    },
    ReportStatus.ASSIGNED: {
        ReportStatus.RESOLVED,
        ReportStatus.REJECTED,
    },
    ReportStatus.RESOLVED: set(),
    ReportStatus.REJECTED: set(),
    ReportStatus.DUPLICATE: set(),
}


def is_valid_status(status: Any) -> bool:
    """Check whether a value is a valid report workflow status."""
    if status is None:
        return False
    try:
        if isinstance(status, ReportStatus):
            return True
        return ReportStatus(status) is not None
    except ValueError:
        return False


def validate_status(status: Any) -> ReportStatus:
    """Normalize a status string to a ReportStatus enum value.

    Unknown statuses are rejected with HTTP 422.
    """
    if status is None:
        raise StatusError(
            "A status must be provided",
            details=[{"field": "status"}]
        )
    try:
        if isinstance(status, ReportStatus):
            return status
        return ReportStatus(status)
    except ValueError:
        raise StatusError(
            f"Unknown status '{status}'. Valid statuses are: "
            f"{', '.join(sorted(s.value for s in ReportStatus))}"
        )


def validate_status_transition(
    current_status: ReportStatus,
    new_status: ReportStatus,
) -> None:
    """Validate that a status transition is allowed.

    Raises ``TransitionNotAllowed`` (HTTP 409) when the transition is not
    permitted by the workflow.
    """
    if new_status == current_status:
        return  # No-op is acceptable.

    allowed = VALID_TRANSITIONS.get(current_status, set())
    if new_status not in allowed:
        allowed_str = ", ".join(sorted(s.value for s in allowed)) if allowed else "no transitions allowed"
        raise TransitionNotAllowed(
            f"Cannot transition report from '{current_status.value}' to '{new_status.value}'. "
            f"Valid next statuses are: {allowed_str}",
            details=[
                {"field": "status", "from": current_status.value, "to": new_status.value},
            ]
        )


def is_terminal_status(status: ReportStatus) -> bool:
    """Return True when the status has no outgoing transitions."""
    return len(VALID_TRANSITIONS.get(status, set())) == 0


# ──────────────────────────────────────────────────────────────────────────
# User / role validation
# ───────── ───────────────────────────────────────────────────────────────

class RoleError(AppError):
    """Raised when a user role is invalid."""

    status_code = 422
    code = "invalid_role"


def is_valid_role(role: Any) -> bool:
    """Check whether a value is a valid application role."""
    if role is None:
        return False
    try:
        if isinstance(role, UserRole):
            return True
        return UserRole(role) is not None
    except ValueError:
        return False


def validate_role(role: Any, allow_reserved: bool = False) -> UserRole:
    """Normalize a role string to a UserRole enum value.

    ``ADMIN`` is reserved for authorized administrative operations and cannot be
    assigned by a normal client request.
    """
    if role is None:
        raise RoleError(
            "A user role must be provided",
            details=[{"field": "role"}]
        )
    try:
        if isinstance(role, UserRole):
            user_role = role
        else:
            user_role = UserRole(role)
    except ValueError:
        raise RoleError(
            f"Unknown role '{role}'. Valid roles are: "
            f"{', '.join(sorted(r.value for r in UserRole))}"
        )

    # ADMIN cannot be assigned through ordinary client requests; it is reserved
    # for authorized administrative operations.
    if user_role == UserRole.ADMIN and not allow_reserved:
        raise RoleError(
            "Admin role cannot be assigned through this endpoint; it is reserved "
            "for authorized administrative operations",
            details=[{"field": "role", "value": "ADMIN"}]
        )

    return user_role


# ──────────────────────────────────────────────────────────────────────────
# Timestamp and identifier validation
# ───────── ───────────────────────────────────────────────────────────────

class IdentifierError(AppError):
    """Raised when an identifier (e.g., UUID) is invalid."""

    status_code = 422
    code = "invalid_identifier"


class TimestampError(AppError):
    """Raised when a timestamp is invalid or out of bounds."""

    status_code = 422
    code = "invalid_timestamp"


def parse_utc_timestamp(value: Any) -> datetime | None:
    """Parse a timestamp string and normalize to a timezone-aware UTC datetime.

    If the timestamp has no timezone information, it is assumed to be UTC.
    """
    if value is None:
        return None

    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)

    if isinstance(value, str):
        cleaned = value.strip()
        if cleaned.endswith("Z") or cleaned.endswith("z"):
            cleaned = cleaned[:-1] + "+00:00"
        try:
            dt = datetime.fromisoformat(cleaned)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.astimezone(timezone.utc)
        except ValueError:
            pass

    raise TimestampError(
        f"Timestamp must be an ISO 8601 string, got: {value!r}",
        details=[{"field": "timestamp", "value": value}]
    )


def validate_uuid(value: Any, field: str = "value") -> str | None:
    """Validate a UUID string.

    Returns the normalized UUID string (canonical lowercase form) or ``None``.
    Raises HTTP 422 for malformed values.
    """
    if value is None:
        return None
    try:
        return str(uuid.UUID(value)).lower()
    except (ValueError, AttributeError):
        raise IdentifierError(
            f"{field} must be a valid UUID string",
            details=[{"field": field, "value": value}]
        )


def now_utc() -> datetime:
    """Return the current time as a timezone-aware UTC datetime."""
    return datetime.now(timezone.utc)


def validate_non_future_server_timestamp(value: datetime | None, field: str = "value") -> datetime | None:
    """Validate a server-owned timestamp is not in the future.

    Server-owned audit timestamps must be set by the server; a client-provided
    timestamp in the future would indicate clock manipulation.
    """
    if value is None:
        return None

    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)

    if value > now_utc():
        raise TimestampError(
            f"{field} must not be in the future (clock skew detected)",
            details=[{"field": field, "value": value.isoformat()}]
        )

    return value