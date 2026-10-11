import os
import subprocess
import sys
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from sqlalchemy import text
from sqlalchemy.exc import OperationalError

import app.database.session as session_mod
from app.database.base import Base
from app.database.session import get_db, get_engine, get_sessionmaker
from tests.conftest import TEST_PASSWORD

BACKEND = Path(__file__).resolve().parents[1]


def _patch_sessionmaker(monkeypatch, session):
    monkeypatch.setattr(session_mod, "get_sessionmaker", lambda: (lambda: session))


def test_get_db_yields_then_closes(monkeypatch):
    fake = MagicMock()
    _patch_sessionmaker(monkeypatch, fake)
    gen = get_db()
    assert next(gen) is fake
    fake.close.assert_not_called()
    with pytest.raises(StopIteration):
        next(gen)
    fake.close.assert_called_once()
    fake.commit.assert_not_called()  # commits belong to the service layer
    fake.rollback.assert_not_called()


def test_get_db_rolls_back_and_closes_on_error(monkeypatch):
    fake = MagicMock()
    _patch_sessionmaker(monkeypatch, fake)
    gen = get_db()
    next(gen)
    with pytest.raises(RuntimeError):
        gen.throw(RuntimeError("handler failed"))
    fake.rollback.assert_called_once()
    fake.close.assert_called_once()


def test_engine_is_lazy_and_uses_configured_postgres_driver():
    from app.core.config import get_settings

    get_settings.cache_clear()
    session_mod.dispose_engine()
    assert get_engine.cache_info().currsize == 0
    engine = get_engine()
    assert engine.dialect.name == "postgresql" and engine.dialect.driver == "psycopg"
    assert engine.url.host == "127.0.0.1" and engine.url.database == "test_db"
    assert get_sessionmaker() is get_sessionmaker()
    session_mod.dispose_engine()


def test_connection_failure_does_not_leak_credentials():
    session_mod.dispose_engine()
    db = get_sessionmaker()()
    try:
        with pytest.raises(OperationalError) as exc:
            db.execute(text("SELECT 1"))
        assert TEST_PASSWORD not in str(exc.value)
    finally:
        db.close()
        session_mod.dispose_engine()


def test_import_app_does_not_connect_to_database():
    """Fresh interpreter: import must succeed and finish quickly with an unreachable DB."""
    code = (
        "import app.main as m; "
        "from app.database.session import get_engine; "
        "assert get_engine.cache_info().currsize == 0, 'engine created at import'; "
        "print('import-ok')"
    )
    r = subprocess.run([sys.executable, "-c", code], cwd=BACKEND, capture_output=True,
                       text=True, timeout=30, env={**os.environ, "PYTHONPATH": str(BACKEND)})
    assert r.returncode == 0, r.stderr
    assert "import-ok" in r.stdout


def test_base_metadata_has_naming_convention_and_no_tables_yet():
    assert Base.metadata.naming_convention["pk"] == "pk_%(table_name)s"
    code = (
        "from app.database.base import Base; "
        "assert len(Base.metadata.tables) == 0, 'tables registered prematurely'; "
        "print('clean-metadata-ok')"
    )
    r = subprocess.run([sys.executable, "-c", code], cwd=BACKEND, capture_output=True,
                       text=True, timeout=30, env={**os.environ, "PYTHONPATH": str(BACKEND)})
    assert r.returncode == 0, r.stderr
    assert "clean-metadata-ok" in r.stdout


# ------------------------------------------------------------ PostgreSQL integration
@pytest.mark.postgres
def test_postgres_select_1(postgres_url):
    from sqlalchemy import create_engine

    engine = create_engine(postgres_url)
    try:
        with engine.connect() as conn:
            assert conn.execute(text("SELECT 1")).scalar() == 1
    finally:
        engine.dispose()


@pytest.mark.postgres
def test_readiness_200_with_real_postgres(app, client, postgres_url):
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker

    engine = create_engine(postgres_url)
    factory = sessionmaker(bind=engine)

    def _db():
        db = factory()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = _db
    try:
        r = client.get("/api/v1/health/ready")
        assert r.status_code == 200 and r.json()["checks"]["database"] == "ok"
    finally:
        engine.dispose()
