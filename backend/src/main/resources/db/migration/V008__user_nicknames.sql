-- Optional, non-unique display nickname for Service users.
--
-- The canonical identity remains users.service_id. Nicknames carry no identity or
-- authorization meaning and may therefore be duplicated freely.
ALTER TABLE users
    ADD COLUMN nickname VARCHAR(64) NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_nickname_length
        CHECK (nickname IS NULL OR char_length(nickname) BETWEEN 1 AND 64);
