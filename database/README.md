# `database/`

Database-related assets that sit outside the Python application. Nothing here is executed automatically.

| Path | Purpose |
|---|---|
| `init/` | Optional first-boot PostgreSQL initialisation scripts. Currently **none are needed** (see `init/README.md`). |
| `seeds/development_rules.sql` | Development disposal-rule seed. Currently a **read-only placeholder** (see "Current status"). |

## Initialisation vs. migrations vs. seeds

| | Owner | Runs | Contains |
|---|---|---|---|
| **Initialisation** | Docker/PostgreSQL entrypoint | once, on an empty data volume | database, role, extensions |
| **Schema migrations** | Alembic (`backend/alembic/versions/`) | explicitly, `alembic upgrade head` | every table, constraint, index |
| **Seed data** | `database/seeds/*.sql` | manually, development only | example rows for existing tables |

Alembic reads the same settings as the application (`backend/.env` / environment) and `Base.metadata` from
`backend/app/models/`. The application never creates tables at startup. Current head: `a1f3c2d4e5b6`
(tables `institutions`, `users`, `waste_reports`, `report_events`).

Project database values (from `docker-compose.yml` / `backend/.env.example` defaults): database `wasteinfoai`,
user `wasteinfoai`, container `wasteinfoai-db`, volume `wasteinfoai_pgdata`, host port `127.0.0.1:5432`.
If you changed these in `backend/.env`, use your values in the commands below.

## Current status of disposal rules

**No disposal-rules table, model, API or content exists**, and no migration was created for this phase.
`development_rules.sql` therefore inserts nothing; it only reports whether a `disposal_rules` table exists.
Three things are missing before real seed data can be written:

1. **Schema**: a SQLAlchemy model and an Alembic migration for disposal rules (never raw `CREATE TABLE` in a seed).
   Suggested shape when the feature is designed: one rule per (waste category, jurisdiction/locale) enforced by a
   unique constraint, a rule text, a source reference, a review status/reviewer (FK to `users`), and timestamps.
2. **Canonical categories**: the 17 labels must first be recorded in `backend/config/waste_categories.json`
   (currently empty) from the trained model's class list. Rules must use exactly those labels.
3. **Reviewed content**: instructions checked against an authoritative source for the target jurisdiction.
   Unreviewed examples must be marked development-only; hazardous-waste, legal and recycling-eligibility
   statements must not be written without verification.

Once these exist, seed with `INSERT ... ON CONFLICT (<unique columns>) DO NOTHING` (never `DO UPDATE`), so reruns
cannot overwrite reviewed data.

## Prerequisites (for any seed)

1. PostgreSQL running and healthy; 2. migrations applied; 3. a **development** database.

## Commands (Windows PowerShell)

From the **repository root**:

```powershell
# 1. Start PostgreSQL (needs backend/.env with POSTGRES_PASSWORD; see backend/README.md)
docker compose --env-file backend/.env up -d db
docker compose ps                                  # wait for "healthy"

# 2. Apply migrations (from backend/ with the virtual environment active)
cd backend; alembic upgrade head; cd ..

# 3. Run the development seed (placeholder today: read-only, safe to repeat)
Get-Content database/seeds/development_rules.sql -Raw |
  docker exec -i wasteinfoai-db psql -U wasteinfoai -d wasteinfoai -v ON_ERROR_STOP=1
```

(`<` input redirection is not supported by PowerShell, hence `Get-Content ... |`.)

### Verify

```powershell
docker exec wasteinfoai-db psql -U wasteinfoai -d wasteinfoai -c "\dt"
docker exec wasteinfoai-db psql -U wasteinfoai -d wasteinfoai -c "SELECT version_num FROM alembic_version;"   # a1f3c2d4e5b6
```

The seed prints one row: `migrations_applied`, `disposal_rules_table_exists`, and a `status` message.
When real rules are added, also check `SELECT count(*) FROM disposal_rules;` before and after a second run: the
count must not change.

## Keeping development seeds out of production

- Seeds are **never** mounted into `/docker-entrypoint-initdb.d/` and never run by the application or Compose.
- Always check the target before running: the `-d` database name, the container/host, and the `database` column
  printed by the seed. Do not point the command at a production host or database.
- Seed rows must be marked development-only and must never include credentials or personal data.
- Do not rely on `ENVIRONMENT=production` to protect you: it configures the API, not `psql`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `connection refused` / `No such container: wasteinfoai-db` | The DB is not running. `docker compose --env-file backend/.env up -d db`, then `docker compose ps`. |
| `password authentication failed` | `.env` password differs from the one the volume was first created with. Fix `.env`; recreating the volume (`docker compose down -v`) **deletes all data**, so do it only for disposable dev data. |
| `relation "..." does not exist` | Migrations not applied. Run `alembic upgrade head` from `backend/`. Seeds must run after migrations. |
| `migrations_applied` is `f` | Same as above, or you are connected to a different database than the one Alembic migrated (check `DATABASE_URL` and `-d`). |
| `alembic` cannot find config / `No module named app` | Run Alembic from `backend/`. |
| Duplicate-key errors on rerun | A seed used a plain `INSERT`. Seeds must use `ON CONFLICT (...) DO NOTHING`. |
| Init scripts did not run | They only run on an empty data volume; use migrations/seeds for anything else. |
