# `database/init/`

**Intentionally contains no SQL scripts.**

## Purpose

Scripts placed in the PostgreSQL image's `/docker-entrypoint-initdb.d/` directory run **only the
first time the data directory is initialised** (an empty volume). They do **not** run on later
container restarts and would not run against the existing `wasteinfoai_pgdata` volume.
This directory is reserved for optional first-boot tasks such as creating a PostgreSQL extension
or an extra role, if the project ever genuinely needs one.

## Why nothing is here

- The database, user and password are already created by the Compose service from
  `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` (`docker-compose.yml`).
- All tables, constraints and indexes are owned by **Alembic** (`backend/alembic/versions/`).
  Creating them here would duplicate and drift from the migrations.
- Seed data depends on tables that only exist after `alembic upgrade head`, which happens
  *after* first boot, so it cannot run from here.
- No PostgreSQL extensions are required by the current schema.

Consequently `docker-compose.yml` does **not** mount this directory. If a script is added later it must be
idempotent and non-destructive (no `DROP DATABASE` / `DROP TABLE`), and mounting it must be a deliberate,
documented Compose change.
