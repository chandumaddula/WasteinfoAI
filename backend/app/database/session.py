"""SQLAlchemy 2.x engine and session management (lazy: nothing connects at import)."""
from __future__ import annotations

import logging
from collections.abc import Iterator
from functools import lru_cache

from sqlalchemy import Engine, create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import get_settings

logger = logging.getLogger(__name__)


@lru_cache
def get_engine() -> Engine:
    """Create the engine once. ``create_engine`` itself does not open a connection."""
    s = get_settings()
    logger.info("Configuring database engine for %s", s.safe_database_url)
    return create_engine(
        s.sqlalchemy_url,
        pool_size=s.db_pool_size,
        max_overflow=s.db_max_overflow,
        pool_recycle=s.db_pool_recycle_seconds,
        pool_pre_ping=True,
        connect_args={"connect_timeout": s.db_connect_timeout_seconds},
        echo=False,  # never echo SQL/parameters; they may contain sensitive data
    )


@lru_cache
def get_sessionmaker() -> sessionmaker[Session]:
    return sessionmaker(bind=get_engine(), autoflush=False, expire_on_commit=False)


def get_db() -> Iterator[Session]:
    """FastAPI dependency yielding one session per request.

    Transaction ownership: the *service layer* calls ``db.commit()`` for the unit of
    work it performs. This dependency never commits implicitly; if the request fails
    with an exception it rolls back, and it always closes the session.
    """
    db = get_sessionmaker()()
    try:
        yield db
    except Exception:
        db.rollback()
        raise
    finally:
        db.close()


def dispose_engine() -> None:
    """Release pooled connections (application shutdown / tests)."""
    if get_engine.cache_info().currsize:
        get_engine().dispose()
    get_sessionmaker.cache_clear()
    get_engine.cache_clear()
