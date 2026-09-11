-- Őrszem V2 — Phase 9: report moderation (soft deletion).
--
-- V001-V004 are immutable, merged history. This migration is additive only: it extends
-- report_assignments' end_reason vocabulary with one new value and adds one new table,
-- report_moderation_episodes. Nothing here alters an earlier table's columns, drops any
-- data, or removes any existing constraint value - the end_reason CHECK is replaced with a
-- strictly wider one (every previously-valid value stays valid).
--
-- Moderation deletion is soft: a report row is never removed and its assignment history is
-- never destroyed (brief §1). "Currently deleted" is answered by one fact alone - whether
-- report_moderation_episodes has a row for this report_id with restored_at IS NULL - never
-- by a flag on `reports` itself. This is deliberate: it means moderation deletion never has
-- to touch `reports.status` in a way that could conflict with V004's own
-- ck_reports_workflow_coherence CHECK (still enforced, unchanged, see below).

-- ---------------------------------------------------------------------------
-- report_assignments: one new end reason
-- ---------------------------------------------------------------------------
-- A moderation deletion that terminates an open IN_PROGRESS assignment ends that episode
-- with MODERATION_DELETED (brief §7) - business ownership history, not the security audit
-- trail. The existing three values are unchanged; this widens the set, it does not replace
-- it, so every row written by Phase 7 stays valid without any data migration.
--
-- 'MODERATION_DELETED' is 19 characters - longer than V004's VARCHAR(16), which fit only the
-- three original values. Widening the column is required before the new value can ever be
-- written; VARCHAR(32) matches report_moderation_episodes.reason's own width below and gives
-- headroom without being unbounded. A plain ALTER COLUMN TYPE on VARCHAR is a metadata-only
-- change in PostgreSQL (no table rewrite), and every existing value already fits.
ALTER TABLE report_assignments ALTER COLUMN end_reason TYPE VARCHAR(32);
ALTER TABLE report_assignments DROP CONSTRAINT ck_report_assignments_end_reason;
ALTER TABLE report_assignments ADD CONSTRAINT ck_report_assignments_end_reason CHECK (
    end_reason IS NULL OR end_reason IN ('RETURNED', 'REASSIGNED', 'ARCHIVED', 'MODERATION_DELETED')
);

-- ---------------------------------------------------------------------------
-- report_moderation_episodes  (append-only moderation history)
-- ---------------------------------------------------------------------------
-- Answers "was this report ever moderation-deleted, and is it currently deleted" - a
-- deliberately different question from report_assignments (operational ownership) and from
-- audit_events (who performed a mutation). One report may be deleted and restored many
-- times over its lifetime; every episode is kept forever, exactly like report_assignments.
--
-- status_before_delete freezes what `reports.status` was at the moment of deletion, so a
-- later restore knows its target without ever having to infer it (brief §9): NEW and
-- IN_PROGRESS both restore to NEW, ARCHIVED restores to ARCHIVED. A deletion that begins
-- from IN_PROGRESS already transitions `reports.status` itself to NEW (unassigned) as part
-- of the same transaction that opens this episode (brief §8) - so `reports.status` is
-- always coherent on its own, under the unchanged V004 CHECK, both while an episode is open
-- and after a later restore closes it. Restoring never has to write `reports.status`,
-- `assigned_user_id` or `archived_at` at all for any of the three cases - only this table's
-- own restored_at/restored_by_user_id/workflow_version bookkeeping changes.
CREATE TABLE report_moderation_episodes (
    id                     UUID         PRIMARY KEY,
    report_id              UUID         NOT NULL REFERENCES reports (id),

    reason                 VARCHAR(32)  NOT NULL,
    deleted_by_user_id     UUID         NOT NULL REFERENCES users (id),
    deleted_at             TIMESTAMPTZ  NOT NULL,
    status_before_delete   VARCHAR(16)  NOT NULL,

    restored_by_user_id    UUID         NULL REFERENCES users (id),
    restored_at            TIMESTAMPTZ  NULL,

    -- The frozen, owner-approved reason vocabulary (brief §4) - stable backend codes only,
    -- never free text, never a "note". OTHER is enum-only, exactly like every other value.
    CONSTRAINT ck_report_moderation_episodes_reason CHECK (
        reason IN ('SPAM', 'TROLL_OR_FALSE_REPORT', 'DUPLICATE', 'INCORRECT', 'IRRELEVANT', 'OTHER')
    ),
    CONSTRAINT ck_report_moderation_episodes_status_before_delete CHECK (
        status_before_delete IN ('NEW', 'IN_PROGRESS', 'ARCHIVED')
    ),
    -- An open episode has neither restore field; a closed one has both. No state may leave
    -- exactly one of the two set - the same coherence shape report_assignments already uses
    -- for its own ended_at/ended_by_user_id/end_reason triple (V004).
    CONSTRAINT ck_report_moderation_episodes_restore_coherence CHECK (
        (restored_at IS NULL     AND restored_by_user_id IS NULL) OR
        (restored_at IS NOT NULL AND restored_by_user_id IS NOT NULL)
    )
);

-- At most ONE open moderation episode per report (brief §6) - database-enforced, the same
-- partial-unique-index technique V004 already uses for "at most one open assignment
-- episode per report" (ux_report_assignments_open_episode). This is the single source of
-- truth for "is this report currently deleted" everywhere in the backend: every ordinary
-- workflow query, every Public status lookup and every moderation mutation all ask exactly
-- this index's question, never a separate flag that could drift from it.
CREATE UNIQUE INDEX ux_report_moderation_episodes_open_episode
    ON report_moderation_episodes (report_id) WHERE restored_at IS NULL;

-- The moderation-history-by-report query the deleted-detail endpoint needs, and the fast
-- path every "is this report currently deleted" lookup uses (report_id, restored_at IS
-- NULL is the leading pair of ux_report_moderation_episodes_open_episode above, but a plain
-- b-tree index also backs the full, non-partial history-by-report query).
CREATE INDEX ix_report_moderation_episodes_report ON report_moderation_episodes (report_id);

-- Backs the deleted-report list (brief §18: deletedAt DESC) and its reason/area filters
-- without a full scan - only ever matched against currently-open episodes.
CREATE INDEX ix_report_moderation_episodes_deleted_at ON report_moderation_episodes (deleted_at DESC) WHERE restored_at IS NULL;
