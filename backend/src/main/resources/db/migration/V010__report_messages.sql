-- =============================================================================
-- Őrszem V2 — V010: one-way messages from service personnel to the reporter
-- =============================================================================
-- Append-only (`report_messages`): once sent, a message cannot be modified or
-- withdrawn. Stores the report reference, the sending service user's ID, the
-- normalized message text (1..1000 characters), and the server timestamp.
-- =============================================================================

CREATE TABLE report_messages (
    id              UUID          PRIMARY KEY,
    report_id       UUID          NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
    sender_user_id  UUID          NOT NULL REFERENCES users(id),
    message_text    VARCHAR(1000) NOT NULL,
    sent_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_report_messages_text_length
        CHECK (btrim(message_text) = message_text AND btrim(message_text) <> '' AND char_length(message_text) <= 1000)
);

CREATE INDEX idx_report_messages_report_sent
    ON report_messages (report_id, sent_at ASC, id ASC);
