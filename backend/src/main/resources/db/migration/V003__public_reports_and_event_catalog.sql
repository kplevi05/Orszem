-- Őrszem V2 — Phase 4: public reporting backend.
--
-- V001 and V002 are merged, immutable history. This migration is additive only: it creates
-- the event taxonomy and the report/routing-snapshot model, and seeds the taxonomy from the
-- frozen catalogue in docs/product/EVENT_CATALOG_V2.md. Nothing here alters an earlier table.

-- ---------------------------------------------------------------------------
-- report_categories / report_event_types  (product taxonomy, not reference data)
-- ---------------------------------------------------------------------------
-- Deliberately different from the Phase 3 national reference-data lifecycle (ADR 0006):
-- this is a small, product-controlled business taxonomy that changes through deliberate,
-- reviewed migrations, not an offline importer. A `code` is a stable identifier once
-- published (docs/product/EVENT_CATALOG_V2.md SS1) - its meaning never changes, and a
-- retired entry is deactivated, never deleted, so a historical report's `event_type_code`
-- always resolves to something.
CREATE TABLE report_categories (
    code           VARCHAR(64)  PRIMARY KEY,
    display_name   VARCHAR(200) NOT NULL,
    display_order  INTEGER      NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT ck_report_categories_code_shape CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_report_categories_display_name_not_blank CHECK (btrim(display_name) <> '')
);

-- Deterministic display ordering is load-bearing, not cosmetic: the Public catalogue API
-- and any future client render categories in this order, so two categories racing for the
-- same position would make the presentation order ambiguous.
CREATE UNIQUE INDEX ux_report_categories_display_order ON report_categories (display_order);

CREATE TABLE report_event_types (
    code           VARCHAR(64)  PRIMARY KEY,
    category_code  VARCHAR(64)  NOT NULL REFERENCES report_categories (code),
    display_name   VARCHAR(200) NOT NULL,
    display_order  INTEGER      NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT ck_report_event_types_code_shape CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_report_event_types_display_name_not_blank CHECK (btrim(display_name) <> '')
);

-- Ordering is unique per category, not globally: two categories may each have an event
-- type at display_order 10, since each is only ever rendered within its own category.
CREATE UNIQUE INDEX ux_report_event_types_category_order
    ON report_event_types (category_code, display_order);

-- The reverse lookup: which event types a category has. Also the FK's supporting index.
CREATE INDEX ix_report_event_types_category ON report_event_types (category_code);

-- ---------------------------------------------------------------------------
-- reports
-- ---------------------------------------------------------------------------
-- `id` is the internal identity and is never exposed publicly; `public_id` is the only
-- identity a Public client is ever given. Keeping them separate means an internal primary
-- key can be used freely in FKs and joins without ever becoming an API contract.
CREATE TABLE reports (
    id                          UUID         PRIMARY KEY,
    public_id                   UUID         NOT NULL,
    client_submission_id        UUID         NOT NULL,

    -- SHA-256 of the client-generated report access credential. Never the credential
    -- itself - see ADR 0008. 32 bytes, checked structurally rather than trusted.
    public_access_credential_hash BYTEA      NOT NULL,

    occurred_at                 TIMESTAMPTZ  NOT NULL,
    submitted_at                TIMESTAMPTZ  NOT NULL,

    train_identifier            VARCHAR(64)  NULL,

    settlement_id                UUID        NOT NULL REFERENCES settlements (id),
    -- The line the reporter selected, if any - the raw client input, kept for its own
    -- sake. It is NOT the routing decision; see report_routing_snapshots for that.
    submitted_railway_line_id    UUID        NULL REFERENCES railway_lines (id),
    event_type_code              VARCHAR(64) NOT NULL REFERENCES report_event_types (code),

    status                       VARCHAR(16) NOT NULL,

    CONSTRAINT ck_reports_status CHECK (status IN ('NEW', 'IN_PROGRESS', 'ARCHIVED')),
    CONSTRAINT ck_reports_credential_hash_length CHECK (octet_length(public_access_credential_hash) = 32),
    CONSTRAINT ck_reports_train_identifier_not_blank
        CHECK (train_identifier IS NULL OR btrim(train_identifier) = train_identifier),
    CONSTRAINT ck_reports_train_identifier_length CHECK (char_length(train_identifier) <= 64)
);

-- The only identity a Public client ever presents back to the API.
CREATE UNIQUE INDEX ux_reports_public_id ON reports (public_id);

-- The idempotency key. One row per client submission attempt, enforced by the database,
-- not by a check-then-insert race in application code.
CREATE UNIQUE INDEX ux_reports_client_submission_id ON reports (client_submission_id);

-- Supports the Service workflow list this phase does not yet build, but which will need
-- exactly this shape: "the NEW reports, oldest first".
CREATE INDEX ix_reports_status_submitted_at ON reports (status, submitted_at);

CREATE INDEX ix_reports_settlement ON reports (settlement_id);
CREATE INDEX ix_reports_event_type ON reports (event_type_code);

-- ---------------------------------------------------------------------------
-- report_routing_snapshots
-- ---------------------------------------------------------------------------
-- The CURRENT routing decision for a report, computed once at submission time from
-- RoutingService (ADR 0007) and stored - not event sourcing, and not a history table. A
-- later phase that deliberately re-routes a NEW report overwrites this row; nothing here
-- prevents that, and nothing here records the prior value. Historical/audit concerns for
-- routing changes are a later, separate decision.
CREATE TABLE report_routing_snapshots (
    report_id                   UUID         PRIMARY KEY REFERENCES reports (id),

    routing_status               VARCHAR(16) NOT NULL,
    routing_reason                VARCHAR(64) NULL,
    resolved_railway_line_id      UUID        NULL REFERENCES railway_lines (id),
    service_area_id               UUID        NULL REFERENCES service_areas (id),

    -- The reference-state revision RoutingService consulted - names which revision was
    -- current when this decision was made, not that every fact used originated in that
    -- specific import (ADR 0007 Decision 2; carried forward unchanged into report routing,
    -- see ADR 0008).
    reference_dataset_version     VARCHAR(64) NOT NULL,

    routed_at                     TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_report_routing_snapshots_status CHECK (routing_status IN ('ROUTED', 'UNCLASSIFIED')),
    CONSTRAINT ck_report_routing_snapshots_reason CHECK (
        routing_reason IS NULL OR routing_reason IN (
            'NO_VERIFIED_RAILWAY_LINE_REFERENCE',
            'RAILWAY_LINE_NOT_SELECTED',
            'REFERENCE_MISMATCH',
            'RAILWAY_LINE_UNASSIGNED',
            'RAILWAY_LINE_INACTIVE',
            'SERVICE_AREA_INACTIVE'
        )
    ),
    -- The coherence rule ADR 0008 documents: ROUTED always names an area and a line and
    -- carries no reason; UNCLASSIFIED always carries a reason and never an area. An
    -- UNCLASSIFIED resolved_railway_line_id is deliberately unconstrained here - it is
    -- NULL for NO_VERIFIED_RAILWAY_LINE_REFERENCE/RAILWAY_LINE_NOT_SELECTED/
    -- REFERENCE_MISMATCH (no line was ever resolved) and present for
    -- RAILWAY_LINE_UNASSIGNED/RAILWAY_LINE_INACTIVE/SERVICE_AREA_INACTIVE (a line was
    -- resolved but the outcome still wasn't ROUTED).
    CONSTRAINT ck_report_routing_snapshots_coherence CHECK (
        (routing_status = 'ROUTED' AND service_area_id IS NOT NULL
            AND resolved_railway_line_id IS NOT NULL AND routing_reason IS NULL)
        OR
        (routing_status = 'UNCLASSIFIED' AND service_area_id IS NULL AND routing_reason IS NOT NULL)
    )
);

-- Where a future Service workflow phase will query "reports currently in my area".
CREATE INDEX ix_report_routing_snapshots_service_area ON report_routing_snapshots (service_area_id);

-- ---------------------------------------------------------------------------
-- seed: the frozen V2 event catalogue (docs/product/EVENT_CATALOG_V2.md)
-- ---------------------------------------------------------------------------
-- Exactly the 7 categories and 61 event types the active document defines, verified
-- programmatically against that document before this migration was written. This is
-- deliberate, versioned taxonomy content - not reference data, and not a place a runtime
-- importer ever touches (see the table comment above).

INSERT INTO report_categories (code, display_name, display_order, active) VALUES
    ('VIOLENCE_DANGER', 'Erőszak és közvetlen veszély', 10, TRUE),
    ('DISTURBANCE_HARASSMENT', 'Rendzavarás és zaklatás', 20, TRUE),
    ('THEFT_PROPERTY', 'Lopás és vagyon elleni esemény', 30, TRUE),
    ('SUSPICIOUS_ACTIVITY', 'Gyanús személy, tárgy vagy tevékenység', 40, TRUE),
    ('MEDICAL_WELFARE', 'Egészségügyi és segítségnyújtási esemény', 50, TRUE),
    ('SAFETY_HAZARD', 'Közlekedésbiztonsági és műszaki veszély', 60, TRUE),
    ('RULE_VIOLATION_OTHER', 'Szabályszegés és egyéb biztonsági esemény', 70, TRUE);

INSERT INTO report_event_types (code, category_code, display_name, display_order, active) VALUES
    ('FIGHT', 'VIOLENCE_DANGER', 'Verekedés', 10, TRUE),
    ('KNIFE_ATTACK', 'VIOLENCE_DANGER', 'Késelés', 20, TRUE),
    ('PHYSICAL_ASSAULT', 'VIOLENCE_DANGER', 'Fizikai bántalmazás', 30, TRUE),
    ('THREAT', 'VIOLENCE_DANGER', 'Fenyegetés', 40, TRUE),
    ('WEAPON_THREAT', 'VIOLENCE_DANGER', 'Fegyverrel fenyegetés', 50, TRUE),
    ('ROBBERY', 'VIOLENCE_DANGER', 'Rablás', 60, TRUE),
    ('PASSENGER_ASSAULT', 'VIOLENCE_DANGER', 'Utas megtámadása', 70, TRUE),
    ('STAFF_ASSAULT', 'VIOLENCE_DANGER', 'Személyzet megtámadása', 80, TRUE),
    ('GROUP_CONFLICT', 'VIOLENCE_DANGER', 'Csoportos konfliktus', 90, TRUE),
    ('SEXUAL_ASSAULT_SUSPECTED', 'VIOLENCE_DANGER', 'Szexuális erőszak gyanúja', 100, TRUE),
    ('LOUD_BEHAVIOR', 'DISTURBANCE_HARASSMENT', 'Hangoskodás', 10, TRUE),
    ('SHOUTING', 'DISTURBANCE_HARASSMENT', 'Kiabálás', 20, TRUE),
    ('AGGRESSIVE_BEHAVIOR', 'DISTURBANCE_HARASSMENT', 'Agresszív viselkedés', 30, TRUE),
    ('DISORDERLY_CONDUCT', 'DISTURBANCE_HARASSMENT', 'Rendbontás', 40, TRUE),
    ('PASSENGER_HARASSMENT', 'DISTURBANCE_HARASSMENT', 'Utasok zaklatása', 50, TRUE),
    ('STAFF_HARASSMENT', 'DISTURBANCE_HARASSMENT', 'Személyzet zaklatása', 60, TRUE),
    ('SEXUAL_HARASSMENT', 'DISTURBANCE_HARASSMENT', 'Szexuális zaklatás', 70, TRUE),
    ('VERBAL_CONFLICT', 'DISTURBANCE_HARASSMENT', 'Szóbeli konfliktus', 80, TRUE),
    ('INTOXICATED_PERSON', 'DISTURBANCE_HARASSMENT', 'Erősen ittas személy', 90, TRUE),
    ('SUSPECTED_DRUG_USE', 'DISTURBANCE_HARASSMENT', 'Kábítószer-használat gyanúja', 100, TRUE),
    ('THEFT', 'THEFT_PROPERTY', 'Lopás', 10, TRUE),
    ('ATTEMPTED_THEFT', 'THEFT_PROPERTY', 'Lopási kísérlet', 20, TRUE),
    ('PICKPOCKETING', 'THEFT_PROPERTY', 'Zsebtolvajlás', 30, TRUE),
    ('BAGGAGE_THEFT', 'THEFT_PROPERTY', 'Csomag vagy poggyász eltulajdonítása', 40, TRUE),
    ('VANDALISM', 'THEFT_PROPERTY', 'Rongálás', 50, TRUE),
    ('GRAFFITI', 'THEFT_PROPERTY', 'Graffiti', 60, TRUE),
    ('SEAT_DAMAGE', 'THEFT_PROPERTY', 'Ülés megrongálása', 70, TRUE),
    ('WINDOW_DAMAGE', 'THEFT_PROPERTY', 'Ablak megrongálása', 80, TRUE),
    ('TRAIN_EQUIPMENT_DAMAGE', 'THEFT_PROPERTY', 'Vonati berendezés megrongálása', 90, TRUE),
    ('SUSPICIOUS_PERSON', 'SUSPICIOUS_ACTIVITY', 'Gyanús személy', 10, TRUE),
    ('SUSPICIOUS_GROUP', 'SUSPICIOUS_ACTIVITY', 'Gyanús csoport', 20, TRUE),
    ('SUSPICIOUS_PACKAGE', 'SUSPICIOUS_ACTIVITY', 'Gyanús csomag', 30, TRUE),
    ('UNATTENDED_PACKAGE', 'SUSPICIOUS_ACTIVITY', 'Elhagyott csomag', 40, TRUE),
    ('SUSPICIOUS_OBJECT', 'SUSPICIOUS_ACTIVITY', 'Gyanús tárgy', 50, TRUE),
    ('SUSPECTED_WEAPON', 'SUSPICIOUS_ACTIVITY', 'Fegyvernek tűnő tárgy', 60, TRUE),
    ('SUSPECTED_EXPLOSIVE', 'SUSPICIOUS_ACTIVITY', 'Robbanóanyag vagy robbanószerkezet gyanúja', 70, TRUE),
    ('TAMPERING_WITH_EQUIPMENT', 'SUSPICIOUS_ACTIVITY', 'Gyanús beavatkozás vasúti berendezésbe', 80, TRUE),
    ('ILLNESS', 'MEDICAL_WELFARE', 'Rosszullét', 10, TRUE),
    ('UNCONSCIOUS_PERSON', 'MEDICAL_WELFARE', 'Eszméletlen személy', 20, TRUE),
    ('INJURED_PERSON', 'MEDICAL_WELFARE', 'Sérült személy', 30, TRUE),
    ('PERSON_NEEDS_HELP', 'MEDICAL_WELFARE', 'Segítségre szoruló személy', 40, TRUE),
    ('CONFUSED_PERSON', 'MEDICAL_WELFARE', 'Zavart vagy dezorientált személy', 50, TRUE),
    ('UNACCOMPANIED_CHILD', 'MEDICAL_WELFARE', 'Felügyelet nélkül maradt gyermek', 60, TRUE),
    ('POSSIBLE_OVERDOSE', 'MEDICAL_WELFARE', 'Túladagolás gyanúja', 70, TRUE),
    ('FIRE_OR_SMOKE', 'SAFETY_HAZARD', 'Tűz vagy füst', 10, TRUE),
    ('DOOR_MALFUNCTION', 'SAFETY_HAZARD', 'Ajtó meghibásodása', 20, TRUE),
    ('BROKEN_GLASS_HAZARD', 'SAFETY_HAZARD', 'Törött üveg vagy sérülésveszély', 30, TRUE),
    ('CARRIAGE_OBSTRUCTION', 'SAFETY_HAZARD', 'Közlekedést akadályozó tárgy', 40, TRUE),
    ('LIQUID_SPILL', 'SAFETY_HAZARD', 'Csúszásveszélyes kiömlött folyadék', 50, TRUE),
    ('EMERGENCY_EXIT_BLOCKED', 'SAFETY_HAZARD', 'Vészkijárat akadályozva', 60, TRUE),
    ('EMERGENCY_EQUIPMENT_DAMAGED', 'SAFETY_HAZARD', 'Vészhelyzeti berendezés sérült', 70, TRUE),
    ('DANGEROUS_BEHAVIOR_NEAR_DOOR', 'SAFETY_HAZARD', 'Veszélyes viselkedés az ajtónál', 80, TRUE),
    ('PERSON_ON_TRACK_OR_DANGER_ZONE', 'SAFETY_HAZARD', 'Személy a vágányon vagy veszélyzónában', 90, TRUE),
    ('ELECTRICAL_OR_SPARK_HAZARD', 'SAFETY_HAZARD', 'Elektromos hiba vagy szikrázás gyanúja', 100, TRUE),
    ('SMOKING', 'RULE_VIOLATION_OTHER', 'Dohányzás', 10, TRUE),
    ('VAPING', 'RULE_VIOLATION_OTHER', 'Elektromos cigaretta használata', 20, TRUE),
    ('LITTERING', 'RULE_VIOLATION_OTHER', 'Szemetelés', 30, TRUE),
    ('DOOR_OBSTRUCTION', 'RULE_VIOLATION_OTHER', 'Ajtó szándékos akadályozása', 40, TRUE),
    ('AISLE_OBSTRUCTION', 'RULE_VIOLATION_OTHER', 'Átjáró szándékos akadályozása', 50, TRUE),
    ('MISUSE_OF_EMERGENCY_EQUIPMENT', 'RULE_VIOLATION_OTHER', 'Vészhelyzeti berendezés indokolatlan használata', 60, TRUE),
    ('OTHER_SAFETY_EVENT', 'RULE_VIOLATION_OTHER', 'Egyéb biztonsági esemény', 70, TRUE);
