"""SQLAlchemy models for wasteinfoAI.

Import every model module here so it is registered on ``Base.metadata``
before Alembic runs autogenerate.
"""
from app.database.base import Base  # noqa: F401

# Models are imported in the following order so that forward references
# between them are resolved correctly.
from app.models import user  # noqa: F401
from app.models import report  # noqa: F401
from app.models import institution  # noqa: F401
from app.models import audit_event  # noqa: F401