import pytest
from pydantic import ValidationError

from app.core.config import Settings


@pytest.fixture()
def make(monkeypatch):
    """Build Settings ignoring .env and the conftest DATABASE_URL/password."""
    for var in ("DATABASE_URL", "POSTGRES_PASSWORD", "ENVIRONMENT"):
        monkeypatch.delenv(var, raising=False)

    def _make(**kw):
        return Settings(_env_file=None, **kw)

    return _make


def test_development_defaults_are_usable(make):
    s = make()
    assert s.environment == "development"
    assert s.sqlalchemy_url.drivername == "postgresql+psycopg"
    assert s.docs_enabled is True


def test_password_special_chars_are_escaped_and_masked(make):
    s = make(postgres_password="p@ss/w:rd#1")
    assert s.sqlalchemy_url.password == "p@ss/w:rd#1"
    assert "p@ss" not in s.safe_database_url
    assert "***" in s.safe_database_url


def test_database_url_overrides_parts_and_normalizes_driver(make):
    s = make(database_url="postgresql://u:pw@dbhost:6543/mydb")
    url = s.sqlalchemy_url
    assert (url.drivername, url.host, url.port, url.database) == ("postgresql+psycopg", "dbhost", 6543, "mydb")


def test_rejects_non_postgres_database_url(make):
    with pytest.raises(ValidationError):
        make(database_url="mysql://u:pw@h/db")


def test_invalid_environment_rejected(make):
    with pytest.raises(ValidationError):
        make(environment="staging")


def test_invalid_api_prefix_rejected(make):
    with pytest.raises(ValidationError):
        make(api_v1_prefix="api/v1/")


def test_production_requires_strong_password_and_no_debug(make):
    with pytest.raises(ValidationError) as exc:
        make(environment="production", debug=True)
    assert "POSTGRES_PASSWORD" in str(exc.value) and "DEBUG" in str(exc.value)


def test_production_rejects_wildcard_cors(make):
    with pytest.raises(ValidationError):
        make(environment="production", postgres_password="a-Strong_unique-pass-123",
             cors_allowed_origins="*")


def test_production_valid_and_docs_disabled(make):
    s = make(environment="production", postgres_password="a-Strong_unique-pass-123")
    assert s.docs_enabled is False


def test_wildcard_with_credentials_rejected(make):
    with pytest.raises(ValidationError):
        make(cors_allowed_origins="*", cors_allow_credentials=True)


def test_validation_error_does_not_leak_password(make):
    secret = "leaky-secret-9876"
    with pytest.raises(ValidationError) as exc:
        make(environment="production", debug=True, postgres_password=secret)
    assert secret not in str(exc.value)


def test_cors_origin_parsing(make):
    s = make(cors_allowed_origins=" http://a.test , http://b.test ,")
    assert s.cors_origins == ["http://a.test", "http://b.test"]
