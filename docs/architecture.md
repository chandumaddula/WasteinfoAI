# wasteinfoAI — Architecture

> Describes what is **actually in this repository** (verified 2026-10-13), and separately what is planned.
> Related: [system workflow](system_workflow.md) · [API documentation](api_documentation.md) · [database schema](database_schema.md)

## 1. Purpose

wasteinfoAI is intended to be an **offline-first waste identification, reporting and cleanup-coordination system**:
a mobile app identifies waste with an on-device classifier, lets residents and institutions report waste (even
without connectivity), and lets reviewers verify those reports and sanitation workers resolve them.

**Only the backend half exists in this repository.** The repository contains the FastAPI backend, its PostgreSQL schema
and migrations, a Docker Compose file for local PostgreSQL, and a `database/` folder. It contains **no Android code, no
ML model files and no inference code.**

## 2. Status at a glance

| Component | Status | Evidence in the repository |
|---|---|---|
| FastAPI backend (`backend/app`) | **Implemented** | 12 application endpoints plus Swagger/OpenAPI (see [API documentation](api_documentation.md)) |
| PostgreSQL + SQLAlchemy 2.x models | **Implemented** | 4 tables ([database schema](database_schema.md)) |
| Alembic migrations | **Implemented** | One revision, head `a1f3c2d4e5b6` |
| Authentication (token **verification**) | **Partial** | JWT bearer validation + DB-backed users/roles. No login, registration or token-issuing endpoint |
| Role-based authorization | **Implemented** | 5 roles enforced by dependencies and services |
| Idempotent report creation | **Implemented** | Unique constraint `(owner_id, client_uuid)` + `ON CONFLICT` |
| Offline-sync **server** endpoints | **Implemented** | `POST /sync/reports`, `GET /sync/changes` |
| Audit trail | **Partial** | `report_events` rows are written; there is **no API to read them** |
| Waste-category validation | **Partial** | Registry mechanism implemented; the registry file is **empty**, so report creation returns 503 until the 17 labels are supplied |
| Disposal guidance / rules | **Not implemented** | No table, model or API. `database/seeds/development_rules.sql` is a placeholder |
| Evidence (image) storage | **Not implemented** | `evidence_ref` is an opaque, unverified string; no upload endpoint or storage layer |
| Docker Compose (PostgreSQL only) | **Implemented** | `docker-compose.yml`, service `db` |
| Backend `Dockerfile` | **Written, not verified** | Never built in this environment; see gap G1 in §10 |
| Android application | **Not present** | No Android sources in the repository |
| On-device ML inference (ReXNet-150, ExecuTorch) | **Not present / unverified** | No model, checkpoint or export code in the repository. The project owner reports a trained 17-class model; ExecuTorch export is unconfirmed |
| CI (GitHub Actions) and PowerShell helper scripts | **Not present** | Planned as separate tasks |
| Maps, chat assistant | **Out of scope / not present** | — |

Test status at last verification: the backend suite passed (155 tests) against a real PostgreSQL 16; without PostgreSQL
91 pass and the 64 PostgreSQL-dependent tests are skipped.

## 3. System context

```
 ┌──────────────────────────── Phone (PLANNED — not in repo) ───────────────────────────┐
 │  Android app ──► on-device classifier (ReXNet-150 → ExecuTorch, unconfirmed)         │
 │       │                                                                              │
 │       └──► local queue of unsent reports (client-generated UUIDs)                    │
 └───────┼──────────────────────────────────────────────────────────────────────────────┘
         │  HTTPS + "Authorization: Bearer <JWT>"          (the ONLY path to the data)
         ▼
 ┌──────────────────────── Backend (IMPLEMENTED: backend/app) ──────────────────────────┐
 │  FastAPI routers  /api/v1/{health, reports, sync}                                    │
 │      │  request/response validation (Pydantic v2)                                    │
 │      ▼                                                                               │
 │  Services: report_service · sync_service · access (visibility/roles) · idempotency   │
 │      │  SQLAlchemy 2.x (psycopg 3)                                                   │
 └──────┼───────────────────────────────────────────────────────────────────────────────┘
        ▼
 ┌─ PostgreSQL 16 (IMPLEMENTED) ─┐      ┌─ Alembic (IMPLEMENTED) ─┐
 │ institutions · users          │◄─────│ explicit `alembic       │
 │ waste_reports · report_events │      │ upgrade head`           │
 └───────────────────────────────┘      └─────────────────────────┘
```

## 4. Components and responsibilities

### 4.1 Android application — planned
Intended responsibilities (none implemented here): capture images, run the classifier, collect location, keep a local
queue of reports while offline, upload with stable client UUIDs, and apply the change feed. Clients must never connect
to PostgreSQL directly; the API is the only access path.

### 4.2 On-device ML — planned / unverified
A ReXNet-150 (`timm`) classifier over 17 waste categories is reported by the project owner (95.88 % test accuracy), with
ExecuTorch export unconfirmed. **None of this is in the repository.** The backend deliberately does not depend on the
model: it never runs inference and never needs the checkpoint. Its only coupling is the **category label list**
(§7), which must match the model's labels exactly.

### 4.3 FastAPI backend — implemented
| Layer | Location | Responsibility |
|---|---|---|
| App factory, middleware, CORS | `app/main.py`, `app/core/middleware.py` | Wiring; `X-Request-ID` on every response; access logging |
| Routers | `app/api/v1/{health,reports,sync}.py` | HTTP concerns only: dependencies, status codes |
| Auth dependencies | `app/api/deps.py` | `get_current_user` (401), `require_roles` (403) |
| Schemas | `app/schemas/` | Request validation / response serialization (Pydantic v2, `extra="forbid"` on requests) |
| Services | `app/services/` | Business rules, transactions (services commit), idempotency, workflow transitions, sync |
| Core | `app/core/` | Settings, errors, JWT, category registry, cursors, validation helpers, clock, logging |
| Models / DB | `app/models/`, `app/database/` | SQLAlchemy models, engine/session (lazy, nothing connects at import) |

Services do not import FastAPI request objects. Routes contain no business logic beyond choosing the status code.

### 4.4 PostgreSQL — implemented
System of record. Uniqueness, check constraints and foreign keys are enforced in the database, not only in Python.
Local development PostgreSQL 16 runs from `docker-compose.yml` (service `db`, loopback port, named volume
`wasteinfoai_pgdata`). See [database schema](database_schema.md).

### 4.5 SQLAlchemy — implemented
SQLAlchemy 2.x declarative models (`Base` in `app/database/base.py` with a deterministic constraint naming
convention). `WasteReport.version` uses SQLAlchemy's `version_id_col` for optimistic concurrency. Idempotent creation
uses a PostgreSQL `INSERT ... ON CONFLICT DO NOTHING`.

### 4.6 Alembic — implemented
The only way the schema changes. Migrations are run explicitly (`alembic upgrade head` from `backend/`); the
application never creates tables on startup. `alembic/env.py` uses the same settings as the application.

## 5. Communication paths

| From → To | Protocol | Status |
|---|---|---|
| Android → backend | HTTPS/JSON, bearer token | Backend side implemented; client absent |
| Backend → PostgreSQL | psycopg 3 / SQLAlchemy | Implemented |
| Alembic → PostgreSQL | same settings as app | Implemented |
| Android → PostgreSQL | **forbidden by design** | — |
| Backend → ML model | none (by design) | — |
| Backend → object storage | none yet | Not implemented |

## 6. Authentication and authorization boundaries

**Authentication (partial).** Requests carry `Authorization: Bearer <JWT>` (HS256, issuer `wasteinfoai`, claims `sub`,
`iss`, `iat`, `exp`). The token identifies the user only. On every request the user is loaded from the `users` table;
**role, institution and active flag always come from the database**, never from the token, so a token cannot carry or
escalate privileges and deactivating a user takes effect immediately. Missing/invalid/expired token, unknown user or
inactive user → HTTP 401.
**There is no endpoint to register users or obtain a token.** `python -m app.cli` is a development-only helper
(disabled when `ENVIRONMENT=production`). A production identity flow is a later stage. No refresh tokens, no revocation
list, no rate limiting.

**Authorization (implemented).** Deny by default; checked in route dependencies *and* again in services.

| Role | Create report | Read | Edit own | Review | Assign | Resolve |
|---|---|---|---|---|---|---|
| `RESIDENT` | ✔ | own reports | ✔ (while `UNDER_REVIEW`) | ✘ | ✘ | ✘ |
| `INSTITUTION` | ✔ | own + same `institution_id` | ✔ own only | ✘ | ✘ | ✘ |
| `REVIEWER` | ✘ | all | ✘ | ✔ | ✔ | ✘ |
| `SANITATION_WORKER` | ✘ | only reports assigned to them | ✘ | ✘ | ✘ | ✔ if assigned |
| `ADMIN` | ✘ | all | ✘ | ✔ | ✔ | ✔ |

Rules: ownership is derived from the token's user, never from the request body (unknown body fields such as
`owner_id`, `role` or `status` are rejected with 422). Insufficient role → **403**. A report the caller may not see →
**404** (existence is concealed). `ADMIN` currently has no endpoints beyond the reviewer/worker operations.

## 7. Waste-category boundary

Categories are validated server-side against one registry file, `backend/config/waste_categories.json`
(override: `WASTE_CATEGORIES_FILE`). Matching is exact (no case folding, no conversion). **The file is currently empty,
because the canonical 17 model labels are not recorded in this repository; with an empty registry the API fails closed
(HTTP 503 `category_registry_unconfigured`).** The labels must be copied from the model's class list. The backend
exposes no endpoint that returns the category list (see gaps).

## 8. Offline operation and synchronization boundaries

| Concern | Server (implemented) | Client (planned) |
|---|---|---|
| Offline capture and queue | — | Store reports locally with a client UUID |
| Safe retry | `client_uuid` unique per owner; identical retry returns the existing report; different content → 409 | Resend until the server confirms |
| "Synchronized" means | Only `ACCEPTED` / `ALREADY_PROCESSED` (or HTTP 200/201 on single create) | Mark synced only after that |
| Conflicts | Optimistic concurrency (`version`); stale edits → 409; a retry never overwrites a reviewer decision | Reload and re-apply |
| Downloading changes | Cursor feed ordered by server `(updated_at, id)` | Persist `next_cursor`; treat items as upserts |
| Clocks | Server timestamps only; `client_created_at` is informational | Do not rely on device time |
| Temporary outages | 503 + `Retry-After`; whole batch safe to resend | Back off and retry |

Boundary: there is no background job queue and no server-persisted "sync state"; synchronization is request-level.
Workflow status (`UNDER_REVIEW`, …) is separate from sync outcomes. Details: [system workflow](system_workflow.md).

## 9. Cross-cutting behaviour

- **Errors:** one JSON shape (`{"error": {code, message, request_id, details?}}`); stack traces, SQL and credentials are never returned.
- **Logging:** structured JSON logs with a request id; URL passwords are masked.
- **Configuration:** a single `Settings` class (environment / ignored `.env`); production refuses debug mode, placeholder secrets and wildcard CORS; Swagger UI is disabled in production.
- **CORS:** explicit origin allow-list.
- **Time:** UTC everywhere, server-generated.

## 10. Gaps and known issues discovered while documenting

| # | Gap | Impact |
|---|---|---|
| G1 | `backend/Dockerfile` copies only `app/`, `alembic/` and `alembic.ini`, **not `config/`** | In the container the category registry is missing, so report creation returns 503 unless `WASTE_CATEGORIES_FILE` points at a mounted file. The image has also not been built here |
| G2 | The category registry is empty | Report creation unusable until the 17 labels are provided |
| G3 | No token-issuing / user-management endpoints | Clients cannot authenticate without the dev CLI |
| G4 | No endpoint to list categories | Client must ship the same list (must stay in sync with the model) |
| G5 | `report_events` is write-only (no read API) | Audit data is not visible through the API |
| G6 | No evidence upload/storage | `evidence_ref` is never verified |
| G7 | No disposal-rules feature | See [`database/README.md`](../database/README.md) |
| G8 | No Android app or ML code | Client-side behaviour in these documents is design intent |
| G9 | Backend documentation from earlier phases lives in `backend/README.md`; this `docs/` set is new | Keep them consistent |
