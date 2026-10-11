from sqlalchemy.exc import OperationalError

from app.database.session import get_db
from tests.conftest import TEST_PASSWORD


def test_root_returns_service_info(client):
    r = client.get("/")
    assert r.status_code == 200
    body = r.json()
    assert body["service"] == "wasteinfoAI API"
    assert body["api_prefix"] == "/api/v1"
    assert body["docs_url"] == "/docs"
    assert body["health_url"] == "/api/v1/health"
    assert TEST_PASSWORD not in r.text and "postgres" not in r.text.lower()


def test_health_ok_without_database(client):
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert {"service", "version", "timestamp"} <= body.keys()


def test_request_id_generated_and_echoed(client):
    assert len(client.get("/api/v1/health").headers["x-request-id"]) == 32
    r = client.get("/api/v1/health", headers={"X-Request-ID": "abc-123"})
    assert r.headers["x-request-id"] == "abc-123"
    # Unsafe ids are replaced rather than reflected.
    r = client.get("/api/v1/health", headers={"X-Request-ID": "bad id<script>"})
    assert r.headers["x-request-id"] != "bad id<script>"


def test_readiness_ok_when_database_available(client, sqlite_db_override):
    r = client.get("/api/v1/health/ready")
    assert r.status_code == 200
    assert r.json()["status"] == "ready"
    assert r.json()["checks"] == {"database": "ok"}


def test_readiness_503_when_database_unreachable_real_connection(client):
    """Default test DB URL points to a closed port: real connection failure, no mocks."""
    r = client.get("/api/v1/health/ready")
    assert r.status_code == 503
    assert r.json()["status"] == "not_ready"
    assert r.json()["checks"] == {"database": "unavailable"}
    assert TEST_PASSWORD not in r.text and "127.0.0.1" not in r.text
    # The process survives the outage.
    assert client.get("/api/v1/health").status_code == 200


def test_readiness_503_when_session_raises(app, client):
    class BrokenSession:
        def execute(self, *_a, **_k):
            raise OperationalError("SELECT 1", {}, Exception("boom"))

    app.dependency_overrides[get_db] = lambda: BrokenSession()
    r = client.get("/api/v1/health/ready")
    assert r.status_code == 503
    assert r.json()["checks"]["database"] == "unavailable"


def test_swagger_and_openapi_available(client):
    assert client.get("/docs").status_code == 200
    paths = client.get("/openapi.json").json()["paths"]
    assert "/api/v1/health" in paths and "/api/v1/health/ready" in paths


def test_cors_allows_configured_origin_only(client):
    ok = client.get("/", headers={"Origin": "http://localhost:3000"})
    assert ok.headers.get("access-control-allow-origin") == "http://localhost:3000"
    bad = client.get("/", headers={"Origin": "http://evil.example"})
    assert "access-control-allow-origin" not in bad.headers
