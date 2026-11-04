-- =============================================================================
-- Őrszem V2 — V009: manual settlement-based service area routing and report note
-- =============================================================================
-- 1. Replaces pre-populated railway-line / pair routing mappings with manual
--    settlement-to-service-area assignment (`service_area_settlements`), where
--    each settlement belongs to at most one service area ("nagy csoport").
-- 2. Relaxes `ck_report_routing_snapshots_coherence` so a report routed directly
--    by settlement does not require a non-null `resolved_railway_line_id`.
-- 3. Adds the optional free-text `note` column (max 256 characters) to `reports`.
-- =============================================================================

CREATE TABLE service_area_settlements (
    settlement_id    UUID        PRIMARY KEY REFERENCES settlements(id) ON DELETE CASCADE,
    service_area_id  UUID        NOT NULL REFERENCES service_areas(id) ON DELETE CASCADE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_service_area_settlements_area
    ON service_area_settlements (service_area_id);

-- Clear pre-populated line/pair routing tables; routing is now configured
-- manually via settlement assignments on service areas. Any previously routed
-- open reports from those discarded mappings become UNCLASSIFIED so zero tickets
-- are lost or stranded and areas can be freely deactivated/deleted.
DELETE FROM service_area_settlement_lines;
DELETE FROM service_area_railway_lines;

UPDATE report_routing_snapshots rs
   SET routing_status = 'UNCLASSIFIED',
       service_area_id = NULL,
       routing_reason = 'NO_VERIFIED_RAILWAY_LINE_REFERENCE'
  FROM reports r
 WHERE rs.report_id = r.id
   AND rs.routing_status = 'ROUTED'
   AND r.status IN ('NEW', 'IN_PROGRESS');

ALTER TABLE report_routing_snapshots
    DROP CONSTRAINT ck_report_routing_snapshots_coherence;

ALTER TABLE report_routing_snapshots
    ADD CONSTRAINT ck_report_routing_snapshots_coherence CHECK (
        (routing_status = 'ROUTED'
            AND service_area_id IS NOT NULL
            AND routing_reason IS NULL)
     OR (routing_status = 'UNCLASSIFIED'
            AND service_area_id IS NULL
            AND routing_reason IS NOT NULL)
    );

ALTER TABLE reports
    ADD COLUMN note VARCHAR(256) NULL
    CONSTRAINT ck_reports_note_length
    CHECK (note IS NULL OR (btrim(note) = note AND btrim(note) <> '' AND char_length(note) <= 256));
