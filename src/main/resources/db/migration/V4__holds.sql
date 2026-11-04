-- Stage 3: holds reserve spendable funds without moving money.

CREATE TABLE holds (
    id                 uuid        PRIMARY KEY,
    source_id          uuid        NOT NULL REFERENCES accounts (id),
    destination_id     uuid        NOT NULL REFERENCES accounts (id),
    amount_minor       bigint      NOT NULL CHECK (amount_minor > 0),
    state              text        NOT NULL CHECK (state IN ('ACTIVE', 'CAPTURED', 'RELEASED')),
    capture_journal_id uuid        UNIQUE REFERENCES journals (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    closed_at          timestamptz,
    CONSTRAINT hold_distinct_accounts CHECK (source_id <> destination_id),
    -- Each state carries exactly the fields it needs.
    CONSTRAINT hold_state_fields CHECK (
        (state = 'ACTIVE'   AND capture_journal_id IS NULL     AND closed_at IS NULL)
     OR (state = 'CAPTURED' AND capture_journal_id IS NOT NULL AND closed_at IS NOT NULL)
     OR (state = 'RELEASED' AND capture_journal_id IS NULL     AND closed_at IS NOT NULL))
);

CREATE INDEX holds_source_state_idx ON holds (source_id, state);
CREATE INDEX holds_destination_idx ON holds (destination_id);

-- Participants and amount are immutable for the application; only the lifecycle columns change.
GRANT SELECT, INSERT ON holds TO ledger_app;
GRANT UPDATE (state, capture_journal_id, closed_at) ON holds TO ledger_app;
