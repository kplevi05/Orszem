-- Őrszem V2 — Phase 3: reference data, service-area scope and routing foundation.
--
-- V001 is immutable and untouched. This migration adds the reference model, the
-- operational configuration that maps it to service areas, and the authorisation link
-- between users and those areas.
--
-- ---------------------------------------------------------------------------
-- FOUR SEPARATE CONCEPTS, DELIBERATELY NOT MERGED
-- ---------------------------------------------------------------------------
-- These are different kinds of fact with different owners and lifecycles, and collapsing
-- them into one convenient mapping table is the mistake this schema exists to prevent:
--
--   A. Reference data      settlements <-> railway_lines
--                          Verified external facts about the country's railway network.
--                          Changes only when a reference dataset is imported.
--
--   B. Service config      railway_lines -> service_areas
--                          How this customer currently divides operational
--                          responsibility. Changes when the organisation changes.
--
--   C. Authorisation       users -> service_areas
--                          Which areas an authenticated user may act in. Changes when
--                          staffing changes.
--
--   D. Routing decision    settlement (+ optional line) -> line -> area | UNCLASSIFIED
--                          Computed, never stored here.
--
-- Consequences that are enforced below rather than left to convention:
--   * settlements carries NO service_area_id. A settlement's area is derived through the
--     railway line, because that is where operational responsibility actually lives.
--   * railway_lines carries NO service_area_id and no user list. The mapping is its own
--     table so the one-area-per-line rule can be a database constraint.
--   * no JSON permission arrays, and no generic polymorphic mapping table.
--   * reference tables contain no authorisation data of any kind.
--
-- Reference data is NOT seeded here. Its lifecycle is an explicit, reviewed maintenance
-- import, not a Flyway migration: a migration cannot be re-run, diffed or rolled back per
-- row, and a three-thousand-row INSERT would make the schema history unreadable.

-- ---------------------------------------------------------------------------
-- settlements
-- ---------------------------------------------------------------------------
CREATE TABLE settlements (
    id           UUID         PRIMARY KEY,

    -- The official KSH settlement identifier, used as the stable external key across
    -- imports. Stored as text, never as a number: it is an identifier, not a quantity,
    -- and parsing it as an integer would destroy the leading zeroes that distinguish
    -- '01234' from '1234'.
    ksh_code     VARCHAR(5)   NOT NULL,

    name         VARCHAR(200) NOT NULL,
    county_code  VARCHAR(10)  NULL,
    county_name  VARCHAR(100) NULL,

    -- Retired settlements are deactivated, never deleted: rows may be referenced by
    -- historical data, and an identifier that disappears and later returns must keep its
    -- original internal UUID.
    active       BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_settlements_ksh_code_shape CHECK (ksh_code ~ '^[0-9]{5}$')
);

-- The stable external identity. An import matches on this to preserve internal UUIDs.
CREATE UNIQUE INDEX ux_settlements_ksh_code ON settlements (ksh_code);

-- Supports the public settlement search, which filters to active rows and matches on name.
CREATE INDEX ix_settlements_active_name ON settlements (name) WHERE active;

-- ---------------------------------------------------------------------------
-- railway_lines
-- ---------------------------------------------------------------------------
CREATE TABLE railway_lines (
    id            UUID         PRIMARY KEY,

    -- The national line number as text. Text because line identifiers are not arithmetic
    -- and some carry letter suffixes; an integer column could not represent them.
    --
    -- The infrastructure manager is deliberately absent from this identity. Ownership and
    -- operation can transfer between managers without the line itself becoming a
    -- different line, as the 2025 Hungarian transfers demonstrate. Encoding "MÁV" into the
    -- identity would have required renaming lines that did not change.
    line_code     VARCHAR(16)  NOT NULL,

    display_name  VARCHAR(200) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX ux_railway_lines_line_code ON railway_lines (line_code);

-- ---------------------------------------------------------------------------
-- settlement_railway_lines  (reference data)
-- ---------------------------------------------------------------------------
-- Validated canonical relationships only.
--
-- There is deliberately no confidence score, no match quality, no "probable" flag and no
-- free-text note. A column like that becomes runtime routing logic by accident: something
-- eventually reads it and decides. Ambiguous candidates are held outside the database, in
-- the offline preparation step, as NEEDS_REVIEW, and only explicitly validated rows are
-- ever imported.
CREATE TABLE settlement_railway_lines (
    settlement_id    UUID NOT NULL REFERENCES settlements (id) ON DELETE CASCADE,
    railway_line_id  UUID NOT NULL REFERENCES railway_lines (id) ON DELETE CASCADE,

    PRIMARY KEY (settlement_id, railway_line_id)
);

-- The reverse lookup: which settlements a line serves.
CREATE INDEX ix_settlement_railway_lines_line ON settlement_railway_lines (railway_line_id);

-- ---------------------------------------------------------------------------
-- reference_dataset_imports  (provenance)
-- ---------------------------------------------------------------------------
-- Proves which canonical dataset produced the current reference state.
CREATE TABLE reference_dataset_imports (
    id                  UUID         PRIMARY KEY,

    dataset_version     VARCHAR(64)  NOT NULL,
    manifest_sha256     BYTEA        NOT NULL,

    imported_at         TIMESTAMPTZ  NOT NULL,

    settlement_count    INTEGER      NOT NULL,
    railway_line_count  INTEGER      NOT NULL,
    mapping_count       INTEGER      NOT NULL,

    -- A summary of sources and their licences, not the imported data itself.
    source_metadata     JSONB        NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT ck_reference_imports_counts
        CHECK (settlement_count >= 0 AND railway_line_count >= 0 AND mapping_count >= 0)
);

-- One row per dataset version. A second import claiming the same version with different
-- content is refused rather than silently overwriting provenance.
CREATE UNIQUE INDEX ux_reference_dataset_imports_version
    ON reference_dataset_imports (dataset_version);

-- ---------------------------------------------------------------------------
-- service_areas  (customer configuration)
-- ---------------------------------------------------------------------------
-- Őrszem/customer configuration, not national reference data. A service-area name means
-- something inside this organisation and nothing outside it, which is exactly why its
-- identity must not leak into the reference tables above.
CREATE TABLE service_areas (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(16)  NOT NULL,

    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_service_areas_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- Two areas with the same name would make every operational decision ambiguous to the
-- humans using them.
CREATE UNIQUE INDEX ux_service_areas_name ON service_areas (name);

-- ---------------------------------------------------------------------------
-- service_area_railway_lines  (current operational configuration)
-- ---------------------------------------------------------------------------
-- Current state only. There is intentionally no history table here: business mutation
-- history belongs in audit_events, and a report will later carry its own routing snapshot
-- so it stays correct even when configuration changes underneath it.
CREATE TABLE service_area_railway_lines (
    service_area_id  UUID NOT NULL REFERENCES service_areas (id) ON DELETE CASCADE,
    railway_line_id  UUID NOT NULL REFERENCES railway_lines (id) ON DELETE CASCADE,

    PRIMARY KEY (service_area_id, railway_line_id)
);

-- The V2 invariant: one complete railway line belongs to at most ONE service area.
--
-- Enforced by the database rather than by application code, because it is the rule that
-- makes routing deterministic. Without it, two concurrent assignments could both succeed
-- and a line would resolve to two areas depending on row order.
--
-- A line with NO area is valid and means reports on it will be UNCLASSIFIED.
CREATE UNIQUE INDEX ux_service_area_railway_lines_line ON service_area_railway_lines (railway_line_id);

-- ---------------------------------------------------------------------------
-- user_service_areas  (current authorisation)
-- ---------------------------------------------------------------------------
-- Current permission only. Grants and revocations are recorded as immutable audit events,
-- so this table never accumulates history and can always be read as "the truth right now".
CREATE TABLE user_service_areas (
    user_id          UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    service_area_id  UUID NOT NULL REFERENCES service_areas (id) ON DELETE CASCADE,

    PRIMARY KEY (user_id, service_area_id)
);

CREATE INDEX ix_user_service_areas_area ON user_service_areas (service_area_id);

-- ---------------------------------------------------------------------------
-- users.global_area_access
-- ---------------------------------------------------------------------------
-- The explicit "every area" permission.
--
-- A boolean rather than a special ServiceArea row named "Minden terület": a fake area
-- would appear in area lists, could be assigned, renamed, deactivated or mapped to a
-- railway line, and every query would need to remember to exclude it.
--
-- Note what this does NOT grant: visibility of UNCLASSIFIED reports. Being permitted in
-- every area is about breadth within normal operational work; triaging traffic that
-- resolved to no area at all is a different, narrower responsibility. See AreaScopePolicy.
ALTER TABLE users ADD COLUMN global_area_access BOOLEAN NOT NULL DEFAULT FALSE;
