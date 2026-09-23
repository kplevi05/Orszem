-- Operational responsibility, separate from verified geographic reference facts.
-- Empty on upgrade: no seed data and no changes to existing report snapshots.
CREATE TABLE service_area_settlement_lines (
    settlement_id UUID NOT NULL,
    railway_line_id UUID NOT NULL,
    service_area_id UUID NOT NULL REFERENCES service_areas(id) ON DELETE RESTRICT,
    PRIMARY KEY (settlement_id, railway_line_id),
    CONSTRAINT fk_service_area_settlement_lines_reference
        FOREIGN KEY (settlement_id, railway_line_id)
        REFERENCES settlement_railway_lines(settlement_id, railway_line_id) ON DELETE RESTRICT
);
CREATE INDEX ix_service_area_settlement_lines_area ON service_area_settlement_lines(service_area_id);
CREATE INDEX ix_service_area_settlement_lines_line ON service_area_settlement_lines(railway_line_id);
