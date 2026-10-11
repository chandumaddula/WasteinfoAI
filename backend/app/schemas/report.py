"""Pydantic v2 schemas for report validation and serialization.

These schemas are used for:
- Request validation (incoming JSON payloads)
- Response serialization (outgoing JSON payloads)
- Internal validation and data transfer objects
"""
from __future__ import annotations

import uuid
from datetime import datetime
from enum import Enum
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from typing_extensions import Self

# Import the canonical waste categories from the ML model labels
WASTE_CATEGORIES = {
    "Battery",
    "cardboard",
    "glass",
    "Keyboard",
    "metal",
    "Microwave",
    "Mobile",
    "Mouse",
    "organic",
    "paper",
    "PCB",
    "plastic",
    "Player",
    "Printer",
    "Television",
    "trash",
    "Washing Machine",
}


class WasteCategory(str, Enum):
    """Canonical waste categories from the trained ML model."""

    BATTERY = "Battery"
    CARDBOARD = "cardboard"
    GLASS = "glass"
    KEYBOARD = "Keyboard"
    METAL = "metal"
    MICROWAVE = "Microwave"
    MOBILE = "Mobile"
    MOUSE = "Mouse"
    ORGANIC = "organic"
    PAPER = "paper"
    PCB = "PCB"
    PLASTIC = "plastic"
    PLAYER = "Player"
    PRINTER = "Printer"
    TELEVISION = "Television"
    TRASH = "trash"
    WASHING_MACHINE = "Washing Machine"


class ReportBase(BaseModel):
    """Base fields common to create and update requests."""

    model_config = ConfigDict(
        str_strip_whitespace=True,
        validate_assignment=True,
        from_attributes=True,
    )

    waste_category: WasteCategory = Field(
        ...,
        description="Type of waste identified (must be one of the 17 canonical categories)",
        examples=["plastic", "paper", "glass"]
    )

    description: Optional[str] = Field(
        None,
        max_length=1000,
        description="Optional detailed description of the waste observation",
        examples=["Plastic bottles and bags near the riverbank"]
    )

    # GPS coordinates (mutually exclusive with manual location fields)
    latitude: Optional[float] = Field(
        None,
        ge=-90.0,
        le=90.0,
        description="Latitude in decimal degrees (-90 to 90)",
        examples=[40.7128]
    )

    longitude: Optional[float] = Field(
        None,
        ge=-180.0,
        le=180.0,
        description="Longitude in decimal degrees (-180 to 180)",
        examples=[-74.0060]
    )

    # Manual location fields (when GPS unavailable or not preferred)
    address_line: Optional[str] = Field(
        None,
        max_length=255,
        description="Street address or location description",
        examples=["123 Main Street"]
    )

    city: Optional[str] = Field(
        None,
        max_length=100,
        description="City or locality",
        examples=["New York"]
    )

    region: Optional[str] = Field(
        None,
        max_length=100,
        description="State, province, or region",
        examples=["NY"]
    )

    country: Optional[str] = Field(
        None,
        max_length=100,
        description="Country name or code",
        examples=["USA", "United States"]
    )

    # Image/evidence reference (opaque string validated by storage layer)
    image_reference: Optional[str] = Field(
        None,
        max_length=512,
        description="Reference to image or evidence (validated by storage layer)",
        examples=["s3://wasteinfo-images/report-123.jpg"]
    )

    @field_validator('description')
    @classmethod
    def validate_description(cls, v: Optional[str]) -> Optional[str]:
        """Reject empty descriptions when provided."""
        if v is not None and v.strip() == "":
            return None
        return v

    @model_validator(mode='after')
    def validate_location_fields(self) -> Self:
        """Validate that location is provided either via GPS or manual fields."""
        # Check if GPS coordinates are provided
        has_gps = self.latitude is not None and self.longitude is not None

        # Check if manual location fields are provided
        has_manual = any([
            self.address_line is not None and self.address_line.strip() != "",
            self.city is not None and self.city.strip() != "",
            self.region is not None and self.region.strip() != "",
            self.country is not None and self.country.strip() != ""
        ])

        # Require either GPS or manual location
        if not has_gps and not has_manual:
            raise ValueError(
                "Either GPS coordinates (latitude and longitude) or manual location "
                "fields (address_line, city, region, country) must be provided"
            )

        # If GPS provided, both must be present
        if (self.latitude is not None) != (self.longitude is not None):
            raise ValueError(
                "Both latitude and longitude must be provided together when using GPS coordinates"
            )

        return self


class ReportCreate(ReportBase):
    """Schema for creating a new waste report."""

    client_uuid: Optional[str] = Field(
        None,
        max_length=36,
        description="Client-generated UUID for idempotent retries (offline-first support)",
        examples=["550e8400-e29b-41d4-a716-446655440000"]
    )

    @field_validator('client_uuid')
    @classmethod
    def validate_client_uuid(cls, v: Optional[str]) -> Optional[str]:
        """Validate UUID format if provided."""
        if v is None:
            return None
        try:
            # This will raise ValueError if not a valid UUID
            uuid.UUID(v)
            return v
        except ValueError:
            raise ValueError("client_uuid must be a valid UUID string")


class ReportUpdate(BaseModel):
    """Schema for updating an existing report (limited fields)."""

    model_config = ConfigDict(
        str_strip_whitespace=True,
        validate_assignment=True,
    )

    description: Optional[str] = Field(
        None,
        max_length=1000,
        description="Updated description",
        examples=["Updated description after site visit"]
    )

    # Location updates are allowed but must follow same validation rules
    latitude: Optional[float] = Field(
        None,
        ge=-90.0,
        le=90.0,
        description="Latitude in decimal degrees (-90 to 90)"
    )

    longitude: Optional[float] = Field(
        None,
        ge=-180.0,
        le=180.0,
        description="Longitude in decimal degrees (-180 to 180)"
    )

    address_line: Optional[str] = Field(
        None,
        max_length=255,
        description="Street address or location description"
    )

    city: Optional[str] = Field(
        None,
        max_length=100,
        description="City or locality"
    )

    region: Optional[str] = Field(
        None,
        max_length=100,
        description="State, province, or region"
    )

    country: Optional[str] = Field(
        None,
        max_length=100,
        description="Country name or code"
    )

    image_reference: Optional[str] = Field(
        None,
        max_length=512,
        description="Reference to image or evidence"
    )

    @field_validator('description')
    @classmethod
    def validate_description(cls, v: Optional[str]) -> Optional[str]:
        """Reject empty descriptions when provided."""
        if v is not None and v.strip() == "":
            return None
        return v

    @model_validator(mode='after')
    def validate_location_fields(self) -> Self:
        """Validate location consistency when location fields are updated."""
        # If any location field is being updated, validate the combination
        has_any_location = any([
            self.latitude is not None,
            self.longitude is not None,
            self.address_line is not None,
            self.city is not None,
            self.region is not None,
            self.country is not None
        ])

        if not has_any_location:
            # No location fields being updated - valid
            return self

        # Check if GPS coordinates are provided (both or neither)
        has_gps = self.latitude is not None and self.longitude is not None
        if (self.latitude is not None) != (self.longitude is not None):
            raise ValueError(
                "Both latitude and longitude must be provided together when using GPS coordinates"
            )

        return self


class ReportResponse(ReportBase):
    """Schema for report responses (includes server-generated fields)."""

    id: str = Field(
        ...,
        description="Server-generated report UUID",
        examples=["123e4567-e89b-12d3-a456-426614174000"]
    )

    client_uuid: Optional[str] = Field(
        None,
        description="Client-generated UUID for idempotency (if provided)",
        examples=["550e8400-e29b-41d4-a716-446655440000"]
    )

    owner_id: str = Field(
        ...,
        description="UUID of the user who owns this report",
        examples=["987f6543-e21b-43d2-c654-321100998765"]
    )

    status: str = Field(
        ...,
        description="Current workflow status of the report",
        examples=["UNDER_REVIEW", "VERIFIED", "RESOLVED"]
    )

    version: int = Field(
        ...,
        description="Optimistic concurrency version (increments on state changes)",
        examples=[0, 1, 2]
    )

    created_at: datetime = Field(
        ...,
        description="Timestamp when report was created (UTC)",
        examples=["2026-10-11T10:30:00Z"]
    )

    updated_at: datetime = Field(
        ...,
        description="Timestamp when report was last updated (UTC)",
        examples=["2026-10-11T14:45:00Z"]
    )

    # Synchronization metadata (for offline clients)
    sync_state: Optional[str] = Field(
        None,
        description="Synchronization state for offline-first clients",
        examples=["RECEIVED", "PROCESSED"]
    )

    sync_received_at: Optional[datetime] = Field(
        None,
        description="When the report was first received by the server",
        examples=["2026-10-11T10:30:00Z"]
    )

    sync_last_attempt_at: Optional[datetime] = Field(
        None,
        description="When the last synchronization attempt was made",
        examples=["2026-10-11T11:00:00Z"]
    )

    sync_retry_count: int = Field(
        default=0,
        description="Number of synchronization retries attempted",
        examples=[0, 1, 2]
    )


class ReportListResponse(BaseModel):
    """Paginated list of reports."""

    model_config = ConfigDict(from_attributes=True)

    reports: list[ReportResponse] = Field(
        ...,
        description="List of reports matching the query"
    )

    total: int = Field(
        ...,
        description="Total number of reports matching the query (before pagination)",
        examples=[150]
    )

    limit: int = Field(
        ...,
        description="Maximum number of reports returned per page",
        examples=[50]
    )

    offset: int = Field(
        ...,
        description="Number of reports skipped before this page",
        examples=[0, 50, 100]
    )

    has_more: bool = Field(
        ...,
        description="Whether there are more reports available after this page",
        examples=[True, False]
    )


class ReviewDecision(str, Enum):
    """Possible reviewer decisions for a report."""

    VERIFY = "VERIFY"
    REJECT = "REJECT"
    MARK_AS_DUPLICATE = "MARK_AS_DUPLICATE"
    ASSIGN = "ASSIGN"


class ReviewRequest(BaseModel):
    """Request for reviewer to make a decision on a report."""

    decision: ReviewDecision = Field(
        ...,
        description="Reviewer's decision for this report"
    )

    reason: Optional[str] = Field(
        None,
        max_length=500,
        description="Optional reason for the decision",
        examples=["Clear evidence of recycling violation"]
    )

    assigned_to_worker_id: Optional[str] = Field(
        None,
        max_length=36,
        description="Worker ID to assign the report to (when decision is ASSIGN)",
        examples=["worker-uuid-here"]
    )

    @model_validator(mode='after')
    def validate_assignment_fields(self) -> Self:
        """Validate that assignment requires a worker ID."""
        if self.decision == ReviewDecision.ASSIGN and not self.assigned_to_worker_id:
            raise ValueError("assigned_to_worker_id is required when decision is ASSIGN")
        if self.decision != ReviewDecision.ASSIGN and self.assigned_to_worker_id:
            raise ValueError("assigned_to_worker_id should only be provided when decision is ASSIGN")
        return self


class SyncRequest(BaseModel):
    """Request for submitting offline-created reports (idempotent create/retry)."""

    model_config = ConfigDict(from_attributes=True)

    # Inherit all fields from ReportCreate for the actual report data
    waste_category: WasteCategory = Field(
        ...,
        description="Type of waste identified"
    )

    description: Optional[str] = Field(
        None,
        max_length=1000,
        description="Optional detailed description"
    )

    latitude: Optional[float] = Field(
        None,
        ge=-90.0,
        le=90.0,
        description="Latitude in decimal degrees"
    )

    longitude: Optional[float] = Field(
        None,
        ge=-180.0,
        le=180.0,
        description="Longitude in decimal degrees"
    )

    address_line: Optional[str] = Field(
        None,
        max_length=255,
        description="Street address or location description"
    )

    city: Optional[str] = Field(
        None,
        max_length=100,
        description="City or locality"
    )

    region: Optional[str] = Field(
        None,
        max_length=100,
        description="State, province, or region"
    )

    country: Optional[str] = Field(
        None,
        max_length=100,
        description="Country name or code"
    )

    image_reference: Optional[str] = Field(
        None,
        max_length=512,
        description="Reference to image or evidence"
    )

    # Required for idempotency
    client_uuid: str = Field(
        ...,
        max_length=36,
        description="Client-generated UUID for idempotent retries",
        examples=["550e8400-e29b-41d4-a716-446655440000"]
    )

    # Optional: client timestamp for conflict resolution
    client_timestamp: Optional[datetime] = Field(
        None,
        description="Timestamp when the report was created on the client device",
        examples=["2026-10-11T09:15:00Z"]
    )


class SyncResponse(BaseModel):
    """Response for synchronization requests."""

    model_config = ConfigDict(from_attributes=True)

    report: ReportResponse = Field(
        ...,
        description="The report that was created or retrieved"
    )

    created: bool = Field(
        ...,
        description="True if a new report was created, False if existing report was returned",
        examples=[True, False]
    )


class ChangesRequest(BaseModel):
    """Request for retrieving changes since a synchronization cursor."""

    since: Optional[datetime] = Field(
        None,
        description="Return reports updated after this timestamp (UTC)",
        examples=["2026-10-11T00:00:00Z"]
    )

    limit: int = Field(
        default=100,
        ge=1,
        le=1000,
        description="Maximum number of reports to return",
        examples=[50, 100]
    )

    # Optional: filter by report status
    status: Optional[str] = Field(
        None,
        description="Filter reports by workflow status",
        examples=["UNDER_REVIEW", "VERIFIED"]
    )


class ChangesResponse(BaseModel):
    """Response containing changes since the requested cursor."""

    model_config = ConfigDict(from_attributes=True)

    reports: list[ReportResponse] = Field(
        ...,
        description="Reports that have changed since the requested timestamp"
    )

    has_more: bool = Field(
        ...,
        description="Whether more changes are available (for pagination)",
        examples=[True, False]
    )

    next_cursor: Optional[datetime] = Field(
        None,
        description="Timestamp to use for next request to get remaining changes",
        examples=["2026-10-11T12:00:00Z"]
    )