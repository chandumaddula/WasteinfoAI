"""Isolated test configuration.

Environment variables are set BEFORE the app is imported. They take priority over any
local ``.env``, and the default database URL points at an unreachable port so a test can
never touch the development database by accident. Real-PostgreSQL tests opt in through
TEST_DATABASE_URL (use a dedicated, disposable database).
"""
import os

TEST_PASSWORD = "test_secret_pw"
os.environ.update(
    {
        "ENVIRONMENT": "testing",
        "DEBUG": "false",
        "LOG_LEVEL": "WARNING",
        "LOG_JSON": "false",
        "DATABASE_URL": f"postgresql+psycopg://test_user:{TEST_PASSWORD}@127.0.0.1:1/test_db",
        "POSTGRES_PASSWORD": TEST_PASSWORD,
        "CORS_ALLOWED_ORIGINS": "http://localhost:3000",
        "CORS_ALLOW_CREDENTIALS": "false",
        "DB_CONNECT_TIMEOUT_SECONDS": "1",
    }
)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import Session, sessionmaker  # noqa: E402
from sqlalchemy.pool import StaticPool  # noqa: E402

from app.core.config import get_settings  # noqa: E402
from app.database.session import dispose_engine, get_db  # noqa: E402


@pytest.fixture()
def app():
    from app.main import create_app

    get_settings.cache_clear()
    application = create_app(get_settings())
    yield application
    application.dependency_overrides.clear()
    dispose_engine()
    get_settings.cache_clear()


@pytest.fixture()
def client(app):
    # raise_server_exceptions=False lets us assert on the 500 JSON body instead of re-raising.
    with TestClient(app, raise_server_exceptions=False) as c:
        yield c


@pytest.fixture()
def sqlite_db_override(app):
    """Replace get_db with an in-memory SQLite session (no PostgreSQL needed)."""
    engine = create_engine("sqlite+pysqlite://", poolclass=StaticPool,
                           connect_args={"check_same_thread": False})
    factory = sessionmaker(bind=engine)

    def _get_db():
        db: Session = factory()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = _get_db
    yield
    engine.dispose()


@pytest.fixture()
def db_session():
    """Isolated SQLite session with all tables created for model/service testing."""
    import app.models  # noqa: F401
    from app.database.base import Base

    engine = create_engine(
        "sqlite+pysqlite://",
        poolclass=StaticPool,
        connect_args={"check_same_thread": False},
    )
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    session = factory()
    try:
        yield session
    finally:
        session.close()
        Base.metadata.drop_all(engine)
        engine.dispose()


@pytest.fixture()
def postgres_url():
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        pytest.skip("TEST_DATABASE_URL not set; skipping PostgreSQL integration test")
    return url
