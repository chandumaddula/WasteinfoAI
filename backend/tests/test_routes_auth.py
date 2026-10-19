"""Tests for authentication endpoints, password hashing, and token validation."""
import uuid
from datetime import datetime, timezone, timedelta
import pytest
from fastapi.testclient import TestClient

from app.core.auth import hash_password, verify_password, issue_user_token
from app.database.session import get_db
from app.models.user import User, UserRole, UserToken


@pytest.fixture()
def client_with_db(app, db_session):
    def _override_get_db():
        yield db_session

    app.dependency_overrides[get_db] = _override_get_db
    with TestClient(app, raise_server_exceptions=False) as c:
        yield c
    app.dependency_overrides.pop(get_db, None)


def test_password_hashing_and_verification():
    raw_password = "correct-horse-battery-staple"
    hashed = hash_password(raw_password)

    assert hashed != raw_password
    assert "$" in hashed
    assert verify_password(raw_password, hashed) is True
    assert verify_password("wrong-password", hashed) is False
    assert verify_password("", hashed) is False


def test_register_endpoint(client_with_db):
    r = client_with_db.post(
        "/api/v1/auth/register",
        json={"email": "newbie@example.com", "password": "securepassword123"},
    )
    assert r.status_code == 201
    body = r.json()
    assert body["email"] == "newbie@example.com"
    assert body["role"] == "RESIDENT"
    assert "password" not in body
    assert "password_hash" not in body

    # Registration with full name
    r3 = client_with_db.post(
        "/api/v1/auth/register",
        json={"email": "chandu@example.com", "password": "securepassword123", "full_name": "Chandu Maddula"},
    )
    assert r3.status_code == 201
    assert r3.json()["full_name"] == "Chandu Maddula"
    assert r3.json()["display_name"] == "Chandu"


def test_token_issuance_and_authentication(client_with_db, db_session):
    # Seed a user with hashed password
    user = User(
        id=str(uuid.uuid4()),
        email="authuser@example.com",
        password_hash=hash_password("mypassword456"),
        role=UserRole.RESIDENT,
        display_name="AuthUser",
        is_active=True,
    )
    db_session.add(user)
    db_session.commit()

    # 1. Invalid email
    r_bad_email = client_with_db.post(
        "/api/v1/auth/token",
        json={"email": "nobody@example.com", "password": "mypassword456"},
    )
    assert r_bad_email.status_code == 401

    # 2. Invalid password
    r_bad_pw = client_with_db.post(
        "/api/v1/auth/token",
        json={"email": "authuser@example.com", "password": "wrongpassword"},
    )
    assert r_bad_pw.status_code == 401

    # 3. Successful token issuance
    r_ok = client_with_db.post(
        "/api/v1/auth/token",
        json={"email": "authuser@example.com", "password": "mypassword456"},
    )
    assert r_ok.status_code == 200
    token_data = r_ok.json()
    assert "token" in token_data
    token = token_data["token"]

    # 4. Access /me endpoint with valid token
    r_me = client_with_db.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r_me.status_code == 200
    me_data = r_me.json()
    assert me_data["email"] == "authuser@example.com"
    assert me_data["role"] == "RESIDENT"
    assert "password_hash" not in me_data

    # 5. Missing Authorization header
    r_no_auth = client_with_db.get("/api/v1/auth/me")
    assert r_no_auth.status_code == 401


def test_expired_token_is_rejected(client_with_db, db_session):
    user = User(
        id=str(uuid.uuid4()),
        email="expireduser@example.com",
        role=UserRole.RESIDENT,
        is_active=True,
    )
    db_session.add(user)
    db_session.commit()

    # Create expired token (1 day in past)
    past = datetime.now(timezone.utc) - timedelta(days=1)
    expired_token_val = str(uuid.uuid4())
    token_obj = UserToken(
        id=str(uuid.uuid4()),
        user_id=user.id,
        token=expired_token_val,
        expires_at=past,
        is_active=True,
    )
    db_session.add(token_obj)
    db_session.commit()

    r = client_with_db.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {expired_token_val}"},
    )
    assert r.status_code == 401
    assert "Invalid or expired token" in r.json()["error"]["message"]


def test_disabled_user_is_rejected(client_with_db, db_session):
    user = User(
        id=str(uuid.uuid4()),
        email="disabled@example.com",
        role=UserRole.RESIDENT,
        is_active=False,
    )
    db_session.add(user)
    db_session.commit()

    token_obj = issue_user_token(db_session, user.id)

    r = client_with_db.get(
        "/api/v1/auth/me",
        headers={"Authorization": f"Bearer {token_obj.token}"},
    )
    assert r.status_code == 403
    assert "User account is disabled" in r.json()["error"]["message"]
