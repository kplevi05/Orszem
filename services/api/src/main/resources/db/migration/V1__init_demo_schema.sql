-- Őrszem Demo v1
-- Flyway migration: initial canonical schema
-- PostgreSQL

CREATE TABLE event_categories (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    label varchar(120) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_event_categories_code UNIQUE (code),
    CONSTRAINT ck_event_categories_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT ck_event_categories_label_not_blank CHECK (btrim(label) <> ''),
    CONSTRAINT ck_event_categories_sort_order_nonnegative CHECK (sort_order >= 0)
);

CREATE INDEX ix_event_categories_active_sort
    ON event_categories (active, sort_order, label);

CREATE TABLE event_types (
    id uuid PRIMARY KEY,
    category_id uuid NOT NULL,
    code varchar(64) NOT NULL,
    label varchar(120) NOT NULL,
    description varchar(500),
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_event_types_category
        FOREIGN KEY (category_id) REFERENCES event_categories(id) ON DELETE RESTRICT,
    CONSTRAINT uq_event_types_code UNIQUE (code),
    CONSTRAINT ck_event_types_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT ck_event_types_label_not_blank CHECK (btrim(label) <> ''),
    CONSTRAINT ck_event_types_sort_order_nonnegative CHECK (sort_order >= 0)
);

CREATE INDEX ix_event_types_category_active_sort
    ON event_types (category_id, active, sort_order, label);

CREATE TABLE users (
    id uuid PRIMARY KEY,
    username varchar(100) NOT NULL,
    display_name varchar(150) NOT NULL,
    password_hash varchar(255) NOT NULL,
    role varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_username_not_blank CHECK (btrim(username) <> ''),
    CONSTRAINT ck_users_display_name_not_blank CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_users_password_hash_not_blank CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_users_demo_role CHECK (role IN ('SERVICE_USER')),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE reports (
    id uuid PRIMARY KEY,
    event_type_id uuid NOT NULL,
    train_identifier varchar(64) NOT NULL,
    settlement varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    status varchar(32) NOT NULL DEFAULT 'NEW',
    accepted_at timestamptz,
    archived_at timestamptz,
    accepted_by_user_id uuid,
    archived_by_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_reports_event_type
        FOREIGN KEY (event_type_id) REFERENCES event_types(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_accepted_by_user
        FOREIGN KEY (accepted_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reports_archived_by_user
        FOREIGN KEY (archived_by_user_id) REFERENCES users(id) ON DELETE RESTRICT,

    CONSTRAINT ck_reports_train_not_blank CHECK (btrim(train_identifier) <> ''),
    CONSTRAINT ck_reports_settlement_not_blank CHECK (btrim(settlement) <> ''),
    CONSTRAINT ck_reports_status CHECK (status IN ('NEW', 'IN_PROGRESS', 'ARCHIVED')),
    CONSTRAINT ck_reports_state_consistency CHECK (
        (
            status = 'NEW'
            AND accepted_at IS NULL
            AND accepted_by_user_id IS NULL
            AND archived_at IS NULL
            AND archived_by_user_id IS NULL
        )
        OR
        (
            status = 'IN_PROGRESS'
            AND accepted_at IS NOT NULL
            AND accepted_by_user_id IS NOT NULL
            AND archived_at IS NULL
            AND archived_by_user_id IS NULL
        )
        OR
        (
            status = 'ARCHIVED'
            AND accepted_at IS NOT NULL
            AND accepted_by_user_id IS NOT NULL
            AND archived_at IS NOT NULL
            AND archived_by_user_id IS NOT NULL
            AND archived_at >= accepted_at
        )
    )
);

CREATE INDEX ix_reports_status_received
    ON reports (status, received_at DESC);

CREATE INDEX ix_reports_event_type_occurred
    ON reports (event_type_id, occurred_at DESC);

CREATE INDEX ix_reports_settlement_occurred
    ON reports (settlement, occurred_at DESC);

CREATE INDEX ix_reports_train_occurred
    ON reports (train_identifier, occurred_at DESC);

CREATE INDEX ix_reports_archived_at
    ON reports (archived_at DESC)
    WHERE status = 'ARCHIVED';

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    actor_user_id uuid,
    action varchar(64) NOT NULL,
    target_type varchar(64) NOT NULL,
    target_id uuid,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT fk_audit_events_actor
        FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_audit_events_action_not_blank CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_events_target_type_not_blank CHECK (btrim(target_type) <> '')
);

CREATE INDEX ix_audit_events_target
    ON audit_events (target_type, target_id, occurred_at DESC);

CREATE INDEX ix_audit_events_actor
    ON audit_events (actor_user_id, occurred_at DESC);
