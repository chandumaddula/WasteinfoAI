# wasteinfoAI — System Workflow

> Related: [architecture](architecture.md) · [API documentation](api_documentation.md) · [database schema](database_schema.md)

Legend — **[Implemented]** exists and is tested in this repository (backend only) · **[Partial]** part exists ·
**[Proposed]** intended design with no code in the repository. Only existing endpoints are referenced; none are invented.

## 1. Overview

| # | Stage | Where it runs | Status |
|---|---|---|---|
| 1 | Image capture | Android | **[Proposed]** |
| 2 | On-device classification | Android + ML runtime | **[Proposed]** (model reported by owner, not in repo) |
| 3 | Category validation | Client list + server registry | **[Partial]** server validation implemented, registry empty |
| 4 | Disposal guidance | Client/server | **[Proposed]** no table, model or API |
| 5 | Location handling | Client + server rules | **[Implemented]** server rules; GPS capture is client work |
| 6 | Authentication & permissions | Server | **[Partial]** verification and roles implemented; no login/token issuance |
| 7 | Report creation | Server (`POST /api/v1/reports`) | **[Implemented]** |
| 8 | Offline queue | Android | **[Proposed]** |
| 9 | Synchronization | `POST /api/v1/sync/reports`, `GET /api/v1/sync/changes` | **[Implemented]** server side |
| 10 | Server validation, review, audit | Server | **[Implemented]** (audit rows written, not readable via API) |

## 2. End-to-end flow

```
 capture ─► classify (on device) ─► user confirms category ─► add location/description/evidence ref
   [Proposed]       [Proposed]               [Proposed]                      [Proposed]
                                                                                 │
                         online? ── yes ──► POST /api/v1/reports ◄───────────────┤  [Implemented]
                            │
                            no ──► local queue ──(connectivity returns)──► POST /api/v1/sync/reports
                                      [Proposed]                              [Implemented]
                                                                                 │
        UNDER_REVIEW ─► reviewer decision ─► (VERIFIED ─► ASSIGNED ─► RESOLVED) | REJECTED | DUPLICATE   [Implemented]
                                                                                 │
                         client pulls GET /api/v1/sync/changes ◄─────────────────┘  [Implemented]
```

## 3. Stage details

### 3.1 Image capture and classification — [Proposed]
The Android app captures an image and a ReXNet-150-based classifier (ExecuTorch export unconfirmed) returns one of 17
categories entirely on the device. The backend never receives the image for inference and never calls the model.
There is no image-upload endpoint; the report carries only an optional `evidence_ref`, an opaque storage key that the
server validates for **format only** and never checks for existence.

### 3.2 Category validation — [Partial]
The report's `waste_category` must exactly equal a label in `backend/config/waste_categories.json`. Unknown labels are
rejected with 422 (`unknown_category`); labels are never converted or guessed. The model's labels and this registry must
be identical. Current state: the registry is **empty**, so the server answers 503 `category_registry_unconfigured`
until the 17 labels are added. There is no endpoint exposing the list.

### 3.3 Disposal guidance — [Proposed]
Nothing exists: no table, model, API or reviewed content. `database/seeds/development_rules.sql` is a read-only
placeholder. See [database schema §9](database_schema.md#9-planned-not-implemented) and
[`database/README.md`](../database/README.md). The report workflow does not depend on guidance.

### 3.4 Location handling — [Implemented] (server rules)
- Coordinates must come as a pair: latitude −90…90 and longitude −180…180.
- If GPS is unavailable the client may send `manual_location` (single line, ≤ 200 chars) instead.
- At least one of the two is required; the server never invents a location. The same rules are enforced by database
  check constraints.
- The server does not geocode, store geometry types or reverse-lookup; coordinates are plain `double precision`.

### 3.5 Authentication and permissions — [Partial]
Every endpoint except the health, root and docs endpoints requires `Authorization: Bearer <token>`. The server
resolves the user, role and institution from the database. Who may do what is tabulated in
[architecture §6](architecture.md#6-authentication-and-authorization-boundaries). Obtaining a token is **not** possible
through the API today (development CLI only).

### 3.6 Report creation — [Implemented]
1. Client sends `POST /api/v1/reports` with a client-generated `client_uuid`.
2. Server validates the payload (schema rules, category registry), derives the owner (and institution) from the user.
3. Server computes a fingerprint of the content and inserts with `ON CONFLICT DO NOTHING` on `(owner_id, client_uuid)`.
4. New → **201** with status `UNDER_REVIEW`. Same key and same content → **200** with the existing report, unchanged.
   Same key, different content → **409** `idempotency_conflict`.
5. A `CREATED` audit event is written in the same transaction.

Reports are never auto-verified. Two reports about the same place with different `client_uuid`s are kept as separate
reports; judging real-world duplicates is a reviewer decision.

### 3.7 Offline queuing — [Proposed]
Intended client behaviour: while offline, save each report locally with a freshly generated `client_uuid`; keep it in
the local queue until the server confirms it. Local queue states belong to the client and are **not** server statuses.

### 3.8 Synchronization — [Implemented] (server side)
**Upload:** `POST /api/v1/sync/reports` accepts a batch (default max 50). Each item is validated and committed
independently and gets its own result:

| Item status | Meaning | Client action |
|---|---|---|
| `ACCEPTED` | Created now | Mark synced; store `report.id`, `version` |
| `ALREADY_PROCESSED` | Identical retry; existing report returned as-is (including any review decision) | Mark synced; replace local copy with the returned one |
| `CONFLICT` | Same `client_uuid`, different content | Do not retry unchanged; needs user/app decision |
| `REJECTED` | Item invalid (schema or unknown category) | Do not retry unchanged; fix or discard |

HTTP 503 (database down, registry unconfigured, …) means temporary failure: resend the **whole** batch — items already
committed come back as `ALREADY_PROCESSED`.

**Download:** `GET /api/v1/sync/changes` returns reports visible to the caller ordered by server `(updated_at, id)`,
paged by an opaque cursor. Any edit, review, assignment or resolution advances the report's `updated_at`, so it
reappears; clients upsert by `id` and ignore items whose `version` is older than their copy. Changes younger than
`SYNC_SETTLE_SECONDS` (default 2 s) are withheld so a late-committing transaction cannot be skipped.

**Offline edits:** `PATCH /api/v1/reports/{id}` requires `expected_version`; if the server revision moved on (for
example a reviewer decided), the answer is **409 `stale_version`** with the current version, and nothing is overwritten.
There is no field-level merge.

Client clocks are never trusted: all ordering uses server timestamps; `client_created_at` is stored for information only.

### 3.9 Server validation, review and audit — [Implemented]
Workflow statuses (separate from sync outcomes):

| From | To | Endpoint | Allowed roles |
|---|---|---|---|
| `UNDER_REVIEW` | `VERIFIED` / `REJECTED` / `DUPLICATE` | `POST /reports/{id}/review` | `REVIEWER`, `ADMIN` |
| `VERIFIED` | `ASSIGNED` | `POST /reports/{id}/assign` | `REVIEWER`, `ADMIN` |
| `ASSIGNED` | `RESOLVED` | `POST /reports/{id}/resolve` | assigned `SANITATION_WORKER`, `ADMIN` |

Every other transition returns **409 `invalid_transition`**; `REJECTED`, `DUPLICATE` and `RESOLVED` are terminal. A
`REJECTED` decision requires a note; `DUPLICATE` requires `duplicate_of_id` (existing, not itself, not already a
duplicate). Optional `expected_version` on decisions yields **409 `stale_version`** if the report changed.

Audit events (`report_events`, append-only, written in the same transaction as the change):

| `event_type` | Written when | Fields |
|---|---|---|
| `CREATED` | New report created (not on idempotent replay) | `to_status = UNDER_REVIEW`, version 1 |
| `UPDATED` | Owner edit that changed something (no-op edits write nothing) | `note = "fields: <names>"` |
| `STATUS_CHANGED` | Review, assignment, resolution | `from_status`, `to_status`, `note`, new version |

Each event records the actor and the resulting report version. **No API exposes events yet.**

## 4. Not covered by any workflow yet
Image upload/storage and verification, disposal guidance, token issuance and user management, audit-event viewing,
category list distribution, notifications, maps and chat. See the gap list in [architecture §10](architecture.md#10-gaps-and-known-issues-discovered-while-documenting).
