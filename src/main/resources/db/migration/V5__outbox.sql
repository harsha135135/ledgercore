-- Stage 4: transactional outbox, plus the demonstration consumer's own inbox and effect.

CREATE TABLE outbox_events (
    id              uuid        PRIMARY KEY,
    audit_event_id  uuid        NOT NULL UNIQUE REFERENCES audit_events (id),
    type            text        NOT NULL,
    schema_version  integer     NOT NULL CHECK (schema_version > 0),
    -- Exact JSON bytes as written, so every delivery of an event carries identical content.
    payload_json    text        NOT NULL,
    state           text        NOT NULL CHECK (state IN ('PENDING', 'PUBLISHED', 'FAILED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- Attempts since the latest manual retry; total_attempts never resets.
    attempts        integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    total_attempts  integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    last_error_code text,
    CONSTRAINT total_covers_attempts CHECK (total_attempts >= attempts),
    CONSTRAINT published_has_timestamp CHECK ((state = 'PUBLISHED') = (published_at IS NOT NULL))
);

CREATE INDEX outbox_pending_delivery_idx ON outbox_events (next_attempt_at, id) WHERE state = 'PENDING';
CREATE INDEX outbox_listing_idx ON outbox_events (state, created_at, id);

GRANT SELECT, INSERT ON outbox_events TO ledger_app;
GRANT UPDATE (state, attempts, total_attempts, next_attempt_at, published_at, last_error_code)
    ON outbox_events TO ledger_app;

-- The consumer records each event ID and applies its effect in one transaction.
CREATE TABLE consumer_inbox (
    event_id     uuid        PRIMARY KEY,
    event_type   text        NOT NULL,
    payload_hash text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now()
);

-- The demonstration effect: a count per event type. Applying an event twice would double-count.
CREATE TABLE consumer_event_counts (
    event_type text   PRIMARY KEY,
    applied    bigint NOT NULL
);

GRANT SELECT, INSERT ON consumer_inbox TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON consumer_event_counts TO ledger_app;
