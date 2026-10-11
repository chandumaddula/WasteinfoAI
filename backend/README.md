# wasteinfoAI — Backend API (foundation)

FastAPI + PostgreSQL + SQLAlchemy 2 + Alembic. Stage 1 contains only the foundation:
configuration, DB connectivity, `/`, `/api/v1/health`, `/api/v1/health/ready`, error handling,
structured logging, CORS, Alembic wiring and tests. Auth, reports, cleanup tasks and offline
sync are added in later stages (register routers in `app/api/v1/router.py`, models in `app/models/`).

Clients (e.g. the future Android app) must never connect to PostgreSQL directly; they use this API.

## Where to run commands

| Command | Directory |
|---|---|
| `docker compose ...` (PostgreSQL) | **project root** (where `docker-compose.yml` is) |
| `python -m venv`, `pip`, `alembic`, `uvicorn`, `pytest` | **`backend/`** |

All commands below are Windows 11 PowerShell.

## 1. Virtual environment and dependencies (in `backend/`)

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r requirements.txt
```

If activation is blocked: `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`, then activate again.

## 2. Configure `.env` (in `backend/`)

```powershell
Copy-Item .env.example .env
notepad .env      # set POSTGRES_PASSWORD to your own value
```

`.env` is git-ignored. Real environment variables override `.env`. Production (`ENVIRONMENT=production`)
refuses to start with `DEBUG=true`, a placeholder DB password, or `*` in `CORS_ALLOWED_ORIGINS`.

## 3. Start PostgreSQL (from the **project root**)

```powershell
cd ..                                   # project root, if you are in backend/
docker compose --env-file backend/.env up -d db
docker compose ps                       # wait until db is "healthy"
docker exec wasteinfoai-db pg_isready -U wasteinfoai -d wasteinfoai
```

The container publishes `127.0.0.1:5432 -> 5432` (loopback only, development only). If a local
PostgreSQL already uses 5432, set `POSTGRES_PORT=5433` in `backend/.env` before starting the container.
Data persists in the named volume `wasteinfoai_pgdata`.
Stop: `docker compose stop db`. Delete data too: `docker compose down -v`.

> The password is applied only when the volume is first created. After changing `POSTGRES_PASSWORD`
> later, run `docker compose down -v` (this deletes the dev data) and start again.

## 4. Migrations (in `backend/`)

Migrations are explicit; the app never creates tables at startup.

```powershell
cd backend
alembic upgrade head          # works with zero migrations too (no-op)
alembic current
# later stages:
alembic revision --autogenerate -m "describe change"
```

Alembic reads the same settings as the app (`app/core/config.py`) and `Base.metadata` from
`app/database/base.py`; import new model modules in `app/models/__init__.py`.

## 5. Run the API (in `backend/`)

```powershell
uvicorn app.main:app --reload
```

- Service info: <http://localhost:8000/>
- Swagger UI: <http://localhost:8000/docs> (ReDoc `/redoc`, disabled in production)
- Liveness: `Invoke-RestMethod http://localhost:8000/api/v1/health`
- Readiness / DB check: `Invoke-RestMethod http://localhost:8000/api/v1/health/ready`
  (HTTP 200 when PostgreSQL answers `SELECT 1`, HTTP 503 otherwise; the API keeps running).

Every response carries an `X-Request-ID` header (also in logs). Errors look like:

```json
{"error": {"code": "validation_error", "message": "...", "request_id": "...", "details": [...]}}
```

## 6. Tests (in `backend/`)

```powershell
pytest
```

Tests need no PostgreSQL and no internet; they use an isolated config whose database URL points at an
unreachable port, so the development database is never touched. Two tests are marked `postgres` and are
skipped unless you point them at a **disposable** database:

```powershell
$env:TEST_DATABASE_URL = "postgresql+psycopg://USER:PASSWORD@127.0.0.1:5432/wasteinfoai_test"
pytest
Remove-Item Env:TEST_DATABASE_URL
```

Run only the non-PostgreSQL tests: `pytest -m "not postgres"`.

## 7. Backend Docker image (optional, in `backend/`)

```powershell
docker build -t wasteinfoai-backend .
docker run --rm -p 8000:8000 --env-file .env -e POSTGRES_HOST=host.docker.internal wasteinfoai-backend
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ModuleNotFoundError: No module named 'app'` | You are not in `backend/`. `cd backend` and rerun (`pytest` and `alembic` find `app` from there). |
| `/api/v1/health/ready` returns 503, or `connection refused` in logs | PostgreSQL is not running or the port/host differs. Check `docker compose ps` (root), `POSTGRES_HOST/PORT` in `.env`. |
| `password authentication failed` | `.env` password differs from the one the volume was created with. Fix `.env`, or `docker compose down -v` and recreate. |
| `Set POSTGRES_PASSWORD in backend/.env ...` from compose | Run compose from the root with `--env-file backend/.env` and make sure the variable is set. |
| Pydantic `ValidationError` at startup (`Invalid production configuration`, `DATABASE_URL must use the 'postgresql+psycopg://' scheme`, ...) | A value in `.env`/environment is invalid. The message names the setting but never prints secret values. |
| `alembic` cannot find config / `No config file 'alembic.ini' found` | Run from `backend/`. |
| Alembic connects to the wrong DB | It uses the same settings as the app: check `DATABASE_URL` (it overrides `POSTGRES_*`) and stray environment variables (`Get-ChildItem Env:POSTGRES*`). |
| Port 8000 in use | `uvicorn app.main:app --reload --port 8001`. |
| Port 5432 in use | Set `POSTGRES_PORT=5433` in `.env`, recreate the container. |
