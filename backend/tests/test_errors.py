import pytest

from app.core.errors import AppError
from app.core.logging_config import _redact


@pytest.fixture()
def err_client(app):
    from fastapi.testclient import TestClient

    @app.get("/_t/validate")
    def validate(n: int):
        return {"n": n}

    @app.get("/_t/app-error")
    def app_error():
        raise AppError("Duplicate thing", status_code=409, code="conflict")

    @app.get("/_t/boom")
    def boom():
        raise RuntimeError("postgresql+psycopg://user:hunter2@db/x exploded")

    with TestClient(app, raise_server_exceptions=False) as c:
        yield c


def _assert_error_shape(r, status, code):
    assert r.status_code == status
    err = r.json()["error"]
    assert err["code"] == code
    assert isinstance(err["message"], str)
    assert err["request_id"] == r.headers["x-request-id"]
    return err


def test_validation_error_structure_does_not_echo_input(err_client):
    r = err_client.get("/_t/validate", params={"n": "super-secret-value"})
    err = _assert_error_shape(r, 422, "validation_error")
    assert err["details"][0]["loc"] == ["query", "n"]
    assert "super-secret-value" not in r.text


def test_application_error(err_client):
    err = _assert_error_shape(err_client.get("/_t/app-error"), 409, "conflict")
    assert err["message"] == "Duplicate thing"


def test_unknown_route_404_uses_error_format(client):
    _assert_error_shape(client.get("/nope"), 404, "not_found")


def test_method_not_allowed_405(client):
    _assert_error_shape(client.post("/api/v1/health"), 405, "method_not_allowed")


def test_unexpected_exception_is_hidden_from_client(err_client, caplog):
    r = err_client.get("/_t/boom")
    _assert_error_shape(r, 500, "internal_server_error")
    assert "hunter2" not in r.text and "RuntimeError" not in r.text and "Traceback" not in r.text
    assert r.json()["error"]["message"] == "An unexpected error occurred"
    # ...but it IS logged for diagnostics (not swallowed).
    assert any("Unhandled exception" in rec.getMessage() for rec in caplog.records)


def test_log_redaction_masks_url_passwords():
    assert "hunter2" not in _redact("conn postgresql+psycopg://user:hunter2@db:5432/x failed")
