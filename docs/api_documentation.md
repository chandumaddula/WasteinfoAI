# wasteinfoAI — API Documentation

> Related: [architecture](architecture.md) · [system workflow](system_workflow.md) · [database schema](database_schema.md)

This document covers the **12 application endpoints that exist** in `backend/app/api` plus the generated OpenAPI/Swagger
pages. Interactive documentation is served at `/docs` and the machine-readable schema at `/openapi.json` (both disabled when
`ENVIRONMENT=production`). Where this text and the OpenAPI schema differ, the code is authoritative.

**How the examples were verified.** Every request/response below was produced by running the application against a real
PostgreSQL 16 database (2026-10-13) and cross-checked against the automated tests. IDs, timestamps, request ids and cursors
are illustrative and differ per call. The registry file in this repository is empty, so examples show
`"<category-label>"` where a real label from `backend/config/waste_categories.json` is required (the verification run used
the test-only labels from `backend/tests/data/`). Tokens are never shown: `Bearer <token>` is a placeholder.

## 1. Conventions

- **Base path:** `/api/v1` (configurable via `API_V1_PREFIX`). Content type `application/json`; timestamps are UTC ISO-8601.
- **Authentication:** `Authorization: Bearer <token>` (JWT). No endpoint issues tokens yet (see [architecture §6](architecture.md#6-authentication-and-authorization-boundaries)).
- **Request ids:** every response has `X-Request-ID`; a safe client-supplied `X-Request-ID` (`[A-Za-z0-9._-]{1,64}`) is echoed.
- **Unknown request fields** are rejected (422); ownership, status, version and timestamps can never be set by clients.
- **Visibility:** unreadable reports return 404, not 403.

### 1.1 Error format
```json
{
  "error": {
    "code": "stale_version",
    "message": "The report has changed since you last loaded it",
    "request_id": "bc819960cfd34431be4532267d4e8a72",
    "details": [{ "current_version": 2 }]
  }
}
```
`details` is optional. Validation `details` items are `{"loc": [...], "msg": "...", "type": "..."}`; submitted values are never echoed.

### 1.2 Error codes
| HTTP | `code` | Meaning |
|---|---|---|
| 401 | `unauthorized` | Missing/invalid/expired token, unknown or inactive user. Response has `WWW-Authenticate: Bearer` |
| 403 | `forbidden` | Authenticated but role not allowed (or not the owner for edits) |
| 404 | `not_found`, `method_not_allowed`(405) | Missing or not visible; wrong method |
| 409 | `idempotency_conflict` | Same `client_uuid`, different content |
| 409 | `stale_version` | `expected_version` ≠ current; `details: [{"current_version": n}]` |
| 409 | `invalid_transition` | Status change not allowed from the current status |
| 409 | `invalid_state` | Edit attempted when status is not `UNDER_REVIEW` |
| 409 | `integrity_conflict` | Unexpected database constraint conflict (generic message) |
| 422 | `validation_error` | Schema or business-rule validation. `details[].type` may be `unknown_category`, `invalid_location`, `invalid_worker`, `invalid_duplicate_target`, `invalid_cursor`, `too_many_items`, `conflicting_params`, `invalid_since` |
| 500 | `internal_server_error` | Unexpected; nothing internal is exposed |
| 503 | `database_unavailable` | Database unreachable / pool exhausted. `Retry-After: 5`. Safe to retry |
| 503 | `category_registry_unconfigured` | Category registry empty/missing |
| 503 | `retry_later` | Rare creation race; retry |

### 1.3 The report object
Returned by every report endpoint (also inside sync responses). `evidence_ref` is an **unverified** reference.

```json
{
  "id": "3fb5632c-0f41-4a09-8813-78ad6686a94f",
  "client_uuid": "6f1f1a52-2a49-4a0c-9d65-0d6f5f5d6b11",
  "owner_id": "779009fb-51d7-410c-9375-315f34c603e6",
  "institution_id": null,
  "waste_category": "<category-label>",
  "description": "Overflowing bin near the school gate",
  "latitude": 21.0,
  "longitude": 70.5,
  "manual_location": null,
  "evidence_ref": "reports/2026/6f1f1a52.jpg",
  "client_created_at": "2026-10-11T04:30:00Z",
  "status": "UNDER_REVIEW",
  "duplicate_of_id": null,
  "assigned_worker_id": null,
  "reviewed_at": null,
  "review_note": null,
  "version": 1,
  "created_at": "2026-10-13T06:20:15.168763Z",
  "updated_at": "2026-10-13T06:20:15.168763Z"
}
```
`status` ∈ `UNDER_REVIEW`, `VERIFIED`, `ASSIGNED`, `RESOLVED`, `REJECTED`, `DUPLICATE`. `version` increases on every change.

## 2. Endpoint index

| Method | Path | Auth | Section |
|---|---|---|---|
| GET | `/` | none | 3.1 |
| GET | `/api/v1/health` | none | 3.2 |
| GET | `/api/v1/health/ready` | none | 3.3 |
| POST | `/api/v1/reports` | `RESIDENT`, `INSTITUTION` | 4.1 |
| GET | `/api/v1/reports` | any role (scoped) | 4.2 |
| GET | `/api/v1/reports/{report_id}` | any role (scoped) | 4.3 |
| PATCH | `/api/v1/reports/{report_id}` | `RESIDENT`/`INSTITUTION` owner | 4.4 |
| POST | `/api/v1/reports/{report_id}/review` | `REVIEWER`, `ADMIN` | 4.5 |
| POST | `/api/v1/reports/{report_id}/assign` | `REVIEWER`, `ADMIN` | 4.6 |
| POST | `/api/v1/reports/{report_id}/resolve` | assigned `SANITATION_WORKER`, `ADMIN` | 4.7 |
| POST | `/api/v1/sync/reports` | `RESIDENT`, `INSTITUTION` | 5.1 |
| GET | `/api/v1/sync/changes` | any role (scoped) | 5.2 |

## 3. Service endpoints (no authentication)

### 3.1 `GET /`
Service information. **200**
```json
{ "service": "wasteinfoAI API", "version": "0.1.0", "api_prefix": "/api/v1", "docs_url": "/docs", "health_url": "/api/v1/health" }
```
(`docs_url` is `null` in production.)

### 3.2 `GET /api/v1/health`
Liveness; does not touch the database. **200**
```json
{ "status": "ok", "service": "wasteinfoAI API", "version": "0.1.0", "timestamp": "2026-10-13T06:20:15.151394Z" }
```

### 3.3 `GET /api/v1/health/ready`
Readiness: runs `SELECT 1`. **200** when reachable:
```json
{ "status": "ready", "service": "wasteinfoAI API", "version": "0.1.0", "timestamp": "2026-10-13T06:20:15.154749Z", "checks": { "database": "ok" } }
```
**503** (same shape) with `"status": "not_ready"` and `"checks": {"database": "unavailable"}`; the process keeps running.

## 4. Reports (all require a bearer token)

### 4.1 `POST /api/v1/reports` — create a report (idempotent)
Roles: `RESIDENT`, `INSTITUTION`. Owner and institution come from the authenticated user.

| Field | Type | Required | Rules |
|---|---|---|---|
| `client_uuid` | UUID | **yes** | Idempotency key, unique per owner |
| `waste_category` | string | **yes** | 1–64 chars; exact registry label (no case folding) |
| `description` | string | **yes** | 1–2000 chars **after** whitespace normalization; never truncated |
| `latitude`, `longitude` | number | with each other | −90…90 / −180…180; finite; both or neither |
| `manual_location` | string | one of coordinates / this | single line ≤ 200 chars |
| `evidence_ref` | string | no | opaque key ≤ 255: segments of `A-Za-z0-9._-` joined by `/`; no URLs, leading `/`, `..` or dot-leading segments. **Existence is not verified** |
| `client_created_at` | datetime | no | must include a timezone; informational only |

Request
```json
{
  "client_uuid": "6f1f1a52-2a49-4a0c-9d65-0d6f5f5d6b11",
  "waste_category": "<category-label>",
  "description": "Overflowing bin near the school gate",
  "latitude": 21.0,
  "longitude": 70.5,
  "evidence_ref": "reports/2026/6f1f1a52.jpg",
  "client_created_at": "2026-10-11T04:30:00Z"
}
```

| Status | When |
|---|---|
| **201** | Created; body is the report object with `status: "UNDER_REVIEW"`, `version: 1` |
| **200** | Identical retry; the existing report is returned **unchanged** (a reviewer decision is never reset) |
| 401 / 403 | Not authenticated / role not `RESIDENT`/`INSTITUTION` |
| **409** `idempotency_conflict` | Same `client_uuid`, different content |
| **422** | Validation (examples below) |
| 503 | Database or category registry unavailable |

**Idempotency contract.** The fingerprint covers `waste_category`, normalized `description`, `latitude`, `longitude`,
`manual_location`, `evidence_ref`. It does **not** cover `client_uuid`, `client_created_at` or server-owned values, so
whitespace-only differences and a different device timestamp are still a retry. Uniqueness is enforced by the database
constraint `(owner_id, client_uuid)`, safe under concurrent requests. The same key from different users yields separate reports.

Conflict (409):
```json
{ "error": { "code": "idempotency_conflict", "message": "A report with this client_uuid already exists with different content", "request_id": "98f5978af9934b8c9faaa8075f7e62bc" } }
```
Validation (422, latitude 95):
```json
{ "error": { "code": "validation_error", "message": "Request validation failed", "request_id": "ae70b76becd44b7ca648127e3e5709de",
  "details": [ { "loc": ["body"], "msg": "Value error, latitude must be between -90 and 90", "type": "value_error" } ] } }
```
Unknown category (422):
```json
{ "error": { "code": "validation_error", "message": "Request validation failed", "request_id": "74911038299d49c895aebfc42d64ef7f",
  "details": [ { "loc": ["body", "waste_category"], "msg": "Unknown waste category", "type": "unknown_category" } ] } }
```

### 4.2 `GET /api/v1/reports` — list visible reports
Any authenticated role; results are scoped (resident: own; institution: own + same institution; reviewer/admin: all; worker: assigned).
Newest first, ordered by `(created_at DESC, id DESC)`.

| Query | Default | Rules |
|---|---|---|
| `limit` | 50 | 1–200 |
| `cursor` | — | opaque `next_cursor` from the previous page (≤ 512 chars); invalid → 422 `invalid_cursor` |
| `status` | — | one of the six statuses |

**200**
```json
{ "items": [ { "...report object..." } ], "next_cursor": null, "has_more": false }
```
`next_cursor` is non-null only when `has_more` is true. Errors: 401, 422, 503.

### 4.3 `GET /api/v1/reports/{report_id}`
Any authenticated role. **200** report object. **404** `not_found` if it does not exist **or the caller may not see it**
(`"message": "Report not found"`); 401; 422 for a malformed UUID; 503.

### 4.4 `PATCH /api/v1/reports/{report_id}` — edit own report (optimistic concurrency)
The owner only, and only while `UNDER_REVIEW`.

| Field | Required | Rules |
|---|---|---|
| `expected_version` | **yes** | integer ≥ 1; the version the edit is based on |
| `waste_category`, `description` | no | cannot be `null`; same rules as creation |
| `latitude`, `longitude`, `manual_location`, `evidence_ref` | no | only sent fields change; `null` clears; resulting location must still be valid (coordinate pair or manual location) |

At least one field besides `expected_version` is required. Status, owner, version and timestamps are not accepted (422).

Request `{"expected_version": 1, "description": "Updated description"}` → **200** report object with `version: 2` and a new `updated_at`.
An edit that changes nothing returns 200 without bumping the version or writing an audit event.

| Status | When |
|---|---|
| **403** | Visible but not the owner (e.g. same-institution colleague, reviewer) |
| **404** | Not visible |
| **409** `stale_version` | `expected_version` is old. Nothing is overwritten |
| **409** `invalid_state` | Status is not `UNDER_REVIEW` |
| **422** | Validation / unknown category / invalid resulting location |

Stale example:
```json
{ "error": { "code": "stale_version", "message": "The report has changed since you last loaded it", "request_id": "bc819960cfd34431be4532267d4e8a72",
  "details": [ { "current_version": 2 } ] } }
```

### 4.5 `POST /api/v1/reports/{report_id}/review` — reviewer decision
Roles: `REVIEWER`, `ADMIN`. Valid only from `UNDER_REVIEW`.

| Field | Required | Rules |
|---|---|---|
| `decision` | **yes** | `VERIFIED`, `REJECTED` or `DUPLICATE` |
| `note` | for `REJECTED` | ≤ 500 chars |
| `duplicate_of_id` | **iff** `DUPLICATE` | must exist, differ from this report and not itself be a `DUPLICATE` (else 422 `invalid_duplicate_target`) |
| `expected_version` | no | if given and stale → 409 `stale_version` |

Request `{"decision": "VERIFIED", "note": "Confirmed", "expected_version": 2}` → **200** report object with `status: "VERIFIED"`,
`reviewed_at` and `review_note` set, version incremented.
**403** for other roles; **404** not visible; **409** `invalid_transition` when not `UNDER_REVIEW`:
```json
{ "error": { "code": "invalid_transition", "message": "Cannot change status from VERIFIED to REJECTED", "request_id": "36e1abc8cfc946d19699fd63237a692d" } }
```
A 403 body: `{"error": {"code": "forbidden", "message": "You do not have permission to perform this action", ...}}`.

### 4.6 `POST /api/v1/reports/{report_id}/assign`
Roles: `REVIEWER`, `ADMIN`. `VERIFIED` → `ASSIGNED`.
Fields: `worker_id` (UUID, **required**; must be an active `SANITATION_WORKER`, else 422 `invalid_worker`), `expected_version` (optional).
Request `{"worker_id": "282d1057-5f59-4787-8893-70dbcad4ea34"}` → **200** report object with `status: "ASSIGNED"` and `assigned_worker_id` set.
Errors: 403, 404, 409 (`invalid_transition` unless `VERIFIED`, `stale_version`), 422.

### 4.7 `POST /api/v1/reports/{report_id}/resolve`
Roles: the **assigned** `SANITATION_WORKER`, or `ADMIN`. `ASSIGNED` → `RESOLVED`.
Fields (all optional): `note` (≤ 500), `expected_version`.
Request `{"note": "Cleaned up"}` → **200** report object with `status: "RESOLVED"`.
Errors: 403 (other roles); **404 for a worker who is not assigned to the report**; 409 (`invalid_transition` unless `ASSIGNED`); 422.

## 5. Offline synchronization (all require a bearer token)

### 5.1 `POST /api/v1/sync/reports` — upload offline-created reports
Roles: `RESIDENT`, `INSTITUTION`. Body: `{"items": [ <report create objects> ]}` with 1 to `SYNC_MAX_BATCH_SIZE` (default 50)
items; each item follows the field rules of [§4.1](#41-post-apiv1reports--create-a-report-idempotent). Items are accepted as raw objects and
validated one by one, so a bad item cannot block the rest. Each item is committed in its own transaction, in order, with the same
idempotency key space as `POST /reports`.

Request
```json
{ "items": [
  { "client_uuid": "880185ac-3645-4d7c-a340-82576a4f0103", "waste_category": "<category-label>", "description": "Offline one", "latitude": 21.0, "longitude": 70.5 },
  { "client_uuid": "1ed45bbe-c073-4612-ad0c-d945f037ff7e", "waste_category": "<category-label>", "description": "bad", "latitude": 999, "longitude": 0 },
  { "client_uuid": "6f1f1a52-2a49-4a0c-9d65-0d6f5f5d6b11", "...": "resend of an earlier report" }
] }
```
**200** (the HTTP status is 200 even when some items are not accepted; read each item)
```json
{
  "results": [
    { "index": 0, "client_uuid": "880185ac-3645-4d7c-a340-82576a4f0103", "status": "ACCEPTED", "report": { "...report object..." }, "error": null },
    { "index": 1, "client_uuid": "1ed45bbe-c073-4612-ad0c-d945f037ff7e", "status": "REJECTED", "report": null,
      "error": { "code": "validation_error", "message": "Report payload is invalid", "request_id": null,
                 "details": [ { "loc": [], "msg": "Value error, latitude must be between -90 and 90", "type": "value_error" } ] } },
    { "index": 2, "client_uuid": "6f1f1a52-2a49-4a0c-9d65-0d6f5f5d6b11", "status": "ALREADY_PROCESSED", "report": { "...current server state..." }, "error": null }
  ],
  "server_time": "2026-10-13T06:20:15.270000Z"
}
```
| Item `status` | Meaning |
|---|---|
| `ACCEPTED` | Created now |
| `ALREADY_PROCESSED` | Identical retry; existing report returned unchanged (reviewer decisions are preserved) |
| `CONFLICT` | Same `client_uuid`, different content (`error.code = idempotency_conflict`), permanent |
| `REJECTED` | Invalid item or unknown category, permanent; do not retry unchanged |

A report is confirmed only by `ACCEPTED` or `ALREADY_PROCESSED`. Request-level errors: 401, 403, 422 (empty batch, more than the maximum
items → `too_many_items`, unknown top-level fields), **503** (temporary failure; resend the whole batch, which is safe). Item errors
have `request_id: null` and, for model-level checks, `loc: []`.

### 5.2 `GET /api/v1/sync/changes` — changes since a cursor
Any authenticated role; scoped like the list endpoint. Ordered by server `(updated_at ASC, id ASC)`.

| Query | Default | Rules |
|---|---|---|
| `cursor` | — | previous `next_cursor`; omit for a full initial sync |
| `since` | — | alternative lower bound on server `updated_at` (must include a timezone); cannot be combined with `cursor` |
| `limit` | 100 | 1–500 |

**200**
```json
{ "items": [ { "...report object..." } ],
  "next_cursor": "<opaque>",
  "has_more": true,
  "server_time": "2026-10-13T06:20:15.271722Z" }
```
Semantics: the cursor marks the last returned row; the next page is everything strictly after it, so ties on `updated_at` are
broken by `id` with no gaps or repeats. With no new changes `items` is empty and the request cursor is echoed in `next_cursor`.
Changes younger than `SYNC_SETTLE_SECONDS` (default 2) are withheld. Treat items as upserts by `id`; ignore items with a lower
`version` than the local copy. Errors: 401, 422 (`invalid_cursor`, `conflicting_params`, `invalid_since`, limit out of range), 503.

## 6. Documentation gaps (what the API does **not** provide)
No endpoint exists for: obtaining or refreshing tokens, registering users or institutions, listing waste categories, uploading or
fetching evidence images, reading audit events, retrieving disposal guidance, deleting reports, or administrative user
management. They are not documented here because they are not implemented. Also note: the generated OpenAPI schema types the sync
`items` as untyped objects (they follow the report-create fields above), and the tests that back these examples are in
`backend/tests/test_reports_api.py`, `test_idempotency.py`, `test_sync.py` and `test_auth_no_db.py`.
