-- Stage 2: idempotency records, committed in the same transaction as the operation they protect.

CREATE TABLE idempotency_requests (
    principal_id    uuid        NOT NULL REFERENCES principals (id),
    -- The operation, plus the target resource for capture, release, reversal and outbox retry.
    operation_scope text        NOT NULL,
    key             text        NOT NULL,
    -- SHA-256 of the canonical semantic fields. Same key with a different hash is a 409.
    request_hash    text        NOT NULL,
    -- IN_PROGRESS exists only inside an uncommitted transaction; a committed row is always COMPLETE.
    state           text        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETE')),
    http_status     integer,
    -- Stored as text, not jsonb, so a replay returns the original bytes exactly.
    response_json   text,
    resource_id     uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (principal_id, operation_scope, key),
    CONSTRAINT complete_has_response CHECK (
        state <> 'COMPLETE' OR (http_status IS NOT NULL AND response_json IS NOT NULL))
);

GRANT SELECT, INSERT ON idempotency_requests TO ledger_app;
GRANT UPDATE (state, http_status, response_json, resource_id) ON idempotency_requests TO ledger_app;
