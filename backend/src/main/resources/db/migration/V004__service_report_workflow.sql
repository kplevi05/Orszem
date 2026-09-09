-- Őrszem V2 — Phase 7: Service report workflow.
--
-- V001, V002 and V003 are immutable, merged history. This migration is additive only: it
-- extends `reports` with the current workflow state a Public-submitted report now carries,
-- and adds one new table, `report_assignments`, for operational-ownership history. Nothing
-- here alters an earlier table's existing columns or constraints, and no reference/product
-- taxonomy data is touched.

-- ---------------------------------------------------------------------------
-- reports: workflow columns
-- ---------------------------------------------------------------------------
-- assigned_user_id is the CURRENT operational owner - always a SERVICE_USER in practice
-- (enforced by application code at assignment time, §53 of the Phase 7 brief; the database
-- cannot check a user's role through a plain FK). workflow_version is the mandatory
-- optimistic-concurrency counter every workflow mutation increments exactly once - never
-- updated_at, which changes for reasons a client cannot predict or reason about.
-- archived_at is set exactly once, when the report reaches its terminal state.
ALTER TABLE reports
    ADD COLUMN assigned_user_id UUID        NULL REFERENCES users (id),
    ADD COLUMN workflow_version BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN archived_at      TIMESTAMPTZ NULL;

-- Report current-state coherence (§5): the workflow columns must always agree with
-- `status`. NEW and ARCHIVED both carry no current assignee; only IN_PROGRESS does, and
-- only ARCHIVED carries an archival timestamp. This is the database-level half of the
-- "current assignment invariant" (§52); the other half - that assigned_user_id, when set,
-- names a SERVICE_USER - is enforced by application code, not by this constraint.
ALTER TABLE reports ADD CONSTRAINT ck_reports_workflow_coherence CHECK (
    (status = 'NEW'         AND assigned_user_id IS NULL     AND archived_at IS NULL) OR
    (status = 'IN_PROGRESS' AND assigned_user_id IS NOT NULL AND archived_at IS NULL) OR
    (status = 'ARCHIVED'    AND assigned_user_id IS NULL     AND archived_at IS NOT NULL)
);

-- Backs "my own IN_PROGRESS reports" (§22) and the assignee-role-invariant consistency
-- check (§53). ix_reports_status_submitted_at (V003) already backs the NEW queue (§18-19);
-- it is not duplicated here.
CREATE INDEX ix_reports_assigned_user_status ON reports (assigned_user_id, status);

-- Backs the Archive list (§23: archived_at DESC). A partial index, since archived_at is
-- NULL for every non-ARCHIVED row and this table's own coherence constraint above already
-- guarantees "archived_at IS NOT NULL" exactly identifies ARCHIVED rows.
CREATE INDEX ix_reports_archived_at ON reports (archived_at) WHERE status = 'ARCHIVED';

-- ---------------------------------------------------------------------------
-- report_assignments  (operational-ownership history)
-- ---------------------------------------------------------------------------
-- Answers "who had operational ownership of this report, and when" - a deliberately
-- different question from audit_events, which answers "who performed this mutation"
-- (§48). Never hard-deleted: a report's assignment history is permanent, append-mostly
-- (only the `ended_*` columns of the currently-open row are ever updated) record.
CREATE TABLE report_assignments (
    id                   UUID         PRIMARY KEY,
    report_id            UUID         NOT NULL REFERENCES reports (id),
    assignee_user_id     UUID         NOT NULL REFERENCES users (id),

    assigned_by_user_id  UUID         NOT NULL REFERENCES users (id),
    assigned_at          TIMESTAMPTZ  NOT NULL,

    ended_at             TIMESTAMPTZ  NULL,
    ended_by_user_id     UUID         NULL REFERENCES users (id),
    end_reason           VARCHAR(16)  NULL,

    CONSTRAINT ck_report_assignments_end_reason CHECK (
        end_reason IS NULL OR end_reason IN ('RETURNED', 'REASSIGNED', 'ARCHIVED')
    ),
    -- An open episode has none of the three "ended" fields; a closed one has all three.
    -- No state may leave exactly one or two of them set.
    CONSTRAINT ck_report_assignments_end_coherence CHECK (
        (ended_at IS NULL     AND ended_by_user_id IS NULL     AND end_reason IS NULL) OR
        (ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL AND end_reason IS NOT NULL)
    )
);

-- At most ONE open assignment episode per report (§6) - the database, not application
-- discipline, is what makes "a report has at most one current assignee" actually true even
-- under concurrent workflow mutations. A partial unique index, the same technique already
-- used for "at most one current reference import" (V002) and "at most one line per area".
CREATE UNIQUE INDEX ux_report_assignments_open_episode ON report_assignments (report_id) WHERE ended_at IS NULL;

-- The assignment-history-by-report query the report detail endpoint needs (§25).
CREATE INDEX ix_report_assignments_report ON report_assignments (report_id);
