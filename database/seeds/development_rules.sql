-- =============================================================================
-- wasteinfoAI -- development disposal-rule seed  (PLACEHOLDER: inserts NOTHING)
-- =============================================================================
-- DEVELOPMENT ONLY. Never run development seed data against a production database.
--
-- Why this file contains no INSERT statements
--   1. Schema gap: the application schema (Alembic head a1f3c2d4e5b6) has no
--      disposal-rules table. It contains only: institutions, users, waste_reports,
--      report_events. No model, service, or API for disposal rules exists either.
--      Tables must be created through a SQLAlchemy model + Alembic migration,
--      never directly in seed SQL.
--   2. Category gap: the canonical 17 category labels are not yet recorded in the
--      repository (backend/config/waste_categories.json is empty). Rules must use
--      those exact labels; inventing or "fixing" ML labels is not allowed.
--   3. Content gap: no disposal instructions reviewed against an authoritative,
--      jurisdiction-specific source exist. Unverified advice (especially for
--      hazardous or recyclable-eligibility questions) must not be seeded.
--
-- What this script does today
--   Read-only status check. It reports whether a `disposal_rules` table exists yet.
--   It changes no data, so it is safe to run any number of times. It does not fail
--   on a database that has not been migrated.
--
-- Run (from the repository root; see database/README.md):
--   Get-Content database/seeds/development_rules.sql -Raw |
--     docker exec -i wasteinfoai-db psql -U wasteinfoai -d wasteinfoai -v ON_ERROR_STOP=1
--
-- When the prerequisites above exist, replace the check below with idempotent inserts:
--   INSERT INTO disposal_rules (...) VALUES (...) ON CONFLICT (<unique columns>) DO NOTHING;
--   * DO NOTHING only. Never DO UPDATE: a rerun must not overwrite reviewed content.
--   * Mark every row as development/unreviewed and cite no source you have not verified.
-- =============================================================================

SELECT current_database()                        AS database,
       to_regclass('public.alembic_version') IS NOT NULL AS migrations_applied,
       to_regclass('public.disposal_rules')  IS NOT NULL AS disposal_rules_table_exists,
       CASE WHEN to_regclass('public.disposal_rules') IS NULL
            THEN 'nothing seeded: no disposal_rules table (see header comment)'
            ELSE 'table exists: replace this placeholder with idempotent inserts'
       END                                       AS status;
