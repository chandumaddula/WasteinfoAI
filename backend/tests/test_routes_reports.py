"""Tests for Reports and Sync HTTP endpoints, permissions, and responses."""
import uuid
import pytest
from fastapi.testclient import TestClient

from app.core.auth import issue_user_token
from app.database.session import get_db
from app.models.report import Report, ReportStatus
from app.models.user import User, UserRole


@pytest.fixture()
def client_with_db(app, db_session):
    def _override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = _override_get_db
    with TestClient(app, raise_server_exceptions=False) as c:
        yield c
    app.dependency_overrides.pop(get_db, None)


@pytest.fixture()
def resident_auth(db_session):
    u = User(
        id=str(uuid.uuid4()),
        email="resident_api@example.com",
        role=UserRole.RESIDENT,
        display_name="Resident API",
        is_active=True,
    )
    db_session.add(u)
    db_session.commit()
    token = issue_user_token(db_session, u.id)
    return u, {"Authorization": f"Bearer {token.token}"}


@pytest.fixture()
def other_resident_auth(db_session):
    u = User(
        id=str(uuid.uuid4()),
        email="other_api@example.com",
        role=UserRole.RESIDENT,
        display_name="Other API",
        is_active=True,
    )
    db_session.add(u)
    db_session.commit()
    token = issue_user_token(db_session, u.id)
    return u, {"Authorization": f"Bearer {token.token}"}


@pytest.fixture()
def reviewer_auth(db_session):
    u = User(
        id=str(uuid.uuid4()),
        email="reviewer_api@example.com",
        role=UserRole.REVIEWER,
        display_name="Reviewer API",
        is_active=True,
    )
    db_session.add(u)
    db_session.commit()
    token = issue_user_token(db_session, u.id)
    return u, {"Authorization": f"Bearer {token.token}"}


def test_create_report_endpoint(client_with_db, resident_auth):
    _, headers = resident_auth
    payload = {
        "waste_category": "plastic",
        "description": "Plastic bottles on pavement",
        "latitude": 13.0827,
        "longitude": 80.2707,
    }
    r = client_with_db.post("/api/v1/reports", json=payload, headers=headers)
    assert r.status_code == 201
    body = r.json()
    assert body["waste_category"] == "plastic"
    assert body["status"] == "UNDER_REVIEW"
    assert body["latitude"] == 13.0827


def test_create_report_validation_failures(client_with_db, resident_auth):
    _, headers = resident_auth

    # Invalid waste category
    r_bad_cat = client_with_db.post(
        "/api/v1/reports",
        json={"waste_category": "uranium", "latitude": 13.0, "longitude": 80.0},
        headers=headers,
    )
    assert r_bad_cat.status_code == 422

    # Missing location entirely
    r_no_loc = client_with_db.post(
        "/api/v1/reports",
        json={"waste_category": "cardboard"},
        headers=headers,
    )
    assert r_no_loc.status_code == 422


def test_list_and_get_reports_visibility(client_with_db, resident_auth, other_resident_auth, reviewer_auth):
    u1, h1 = resident_auth
    u2, h2 = other_resident_auth
    _, h_rev = reviewer_auth

    # Resident 1 creates report
    r1 = client_with_db.post(
        "/api/v1/reports",
        json={"waste_category": "glass", "address_line": "R1 Place", "city": "Chennai"},
        headers=h1,
    ).json()

    # Resident 2 creates report
    r2 = client_with_db.post(
        "/api/v1/reports",
        json={"waste_category": "metal", "address_line": "R2 Place", "city": "Chennai"},
        headers=h2,
    ).json()

    # Resident 1 lists reports -> only sees their own
    list_r1 = client_with_db.get("/api/v1/reports", headers=h1).json()
    assert list_r1["total"] == 1
    assert list_r1["reports"][0]["id"] == r1["id"]

    # Resident 1 tries to get Resident 2's report -> 404 (not found / forbidden for resident)
    r_forbidden = client_with_db.get(f"/api/v1/reports/{r2['id']}", headers=h1)
    assert r_forbidden.status_code == 404

    # Reviewer lists reports -> sees both
    list_rev = client_with_db.get("/api/v1/reports", headers=h_rev).json()
    assert list_rev["total"] >= 2

    # Reviewer can inspect Resident 2's report directly
    r_rev_ok = client_with_db.get(f"/api/v1/reports/{r2['id']}", headers=h_rev)
    assert r_rev_ok.status_code == 200


def test_update_report_endpoint(client_with_db, resident_auth):
    _, headers = resident_auth
    r = client_with_db.post(
        "/api/v1/reports",
        json={"waste_category": "paper", "address_line": "Street A", "city": "Chennai"},
        headers=headers,
    ).json()
    report_id = r["id"]

    # Update description
    update_res = client_with_db.patch(
        f"/api/v1/reports/{report_id}",
        json={"description": "Updated note"},
        headers=headers,
    )
    assert update_res.status_code == 200
    assert update_res.json()["description"] == "Updated note"
    assert update_res.json()["version"] == 1


def test_review_endpoint_permissions(client_with_db, resident_auth, reviewer_auth):
    _, h_res = resident_auth
    _, h_rev = reviewer_auth

    # Create report
    r = client_with_db.post(
        "/api/v1/reports",
        json={"waste_category": "PCB", "address_line": "E-waste site", "city": "Chennai"},
        headers=h_res,
    ).json()
    report_id = r["id"]

    # Resident attempts to review -> 403 Forbidden
    r_unauth = client_with_db.post(
        f"/api/v1/reports/{report_id}/review",
        json={"decision": "VERIFY", "reason": "Self-verification"},
        headers=h_res,
    )
    assert r_unauth.status_code == 403

    # Reviewer reviews -> 200 OK
    r_review = client_with_db.post(
        f"/api/v1/reports/{report_id}/review",
        json={"decision": "VERIFY", "reason": "Inspected on site"},
        headers=h_rev,
    )
    assert r_review.status_code == 200
    assert r_review.json()["status"] == "VERIFIED"


def test_sync_endpoints(client_with_db, resident_auth):
    _, headers = resident_auth
    client_uuid = str(uuid.uuid4())

    sync_payload = {
        "waste_category": "Mouse",
        "address_line": "IT Park",
        "city": "Chennai",
        "client_uuid": client_uuid,
    }

    # 1. Post to sync endpoint
    r1 = client_with_db.post("/api/v1/sync/report", json=sync_payload, headers=headers)
    assert r1.status_code == 200
    assert r1.json()["created"] is True
    report_id = r1.json()["report"]["id"]

    # 2. Retry sync with same client_uuid
    r2 = client_with_db.post("/api/v1/sync/report", json=sync_payload, headers=headers)
    assert r2.status_code == 200
    assert r2.json()["created"] is False
    assert r2.json()["report"]["id"] == report_id

    # 3. Pull changes
    r_changes = client_with_db.get("/api/v1/sync/changes", headers=headers)
    assert r_changes.status_code == 200
    changes_body = r_changes.json()
    assert len(changes_body["reports"]) >= 1
    assert any(rep["id"] == report_id for rep in changes_body["reports"])
