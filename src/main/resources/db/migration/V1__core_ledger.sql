-- Stage 1: principals, accounts, journals, postings and audit events.
-- Runs as the schema owner. The application connects as ledger_app with narrower grants.

CREATE TABLE principals (
    id               uuid        PRIMARY KEY,
    external_subject text        NOT NULL UNIQUE,
    role             text        NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN')),
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id                   uuid        PRIMARY KEY,
    owner_id             uuid        REFERENCES principals (id),
    kind                 text        NOT NULL CHECK (kind IN ('CUSTOMER', 'SYSTEM_CONTROL')),
    currency             text        NOT NULL CHECK (currency = 'INR'),
    state                text        NOT NULL CHECK (state IN ('ACTIVE', 'CLOSED')),
    credit_balance_minor bigint      NOT NULL DEFAULT 0,
    reserved_minor       bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    -- Row-level backstops. Cross-row rules (balanced journals, projection = history)
    -- are enforced by the services and checked by reconciliation.
    CONSTRAINT customer_account_rules CHECK (
        kind <> 'CUSTOMER'
        OR (owner_id IS NOT NULL
            AND credit_balance_minor >= 0
            AND reserved_minor >= 0
            AND reserved_minor <= credit_balance_minor)),
    CONSTRAINT system_account_rules CHECK (
        kind <> 'SYSTEM_CONTROL'
        OR (owner_id IS NULL AND reserved_minor = 0))
);

-- At most one funding-control account; readiness checks that exactly one exists.
CREATE UNIQUE INDEX accounts_single_system_control ON accounts (kind) WHERE kind = 'SYSTEM_CONTROL';
CREATE INDEX accounts_owner_idx ON accounts (owner_id);

CREATE TABLE journals (
    id           uuid        PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('FUNDING', 'TRANSFER', 'CAPTURE', 'REVERSAL')),
    amount_minor bigint      NOT NULL CHECK (amount_minor > 0),
    actor_id     uuid        NOT NULL REFERENCES principals (id),
    reverses_id  uuid        UNIQUE REFERENCES journals (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reversal_reference CHECK ((kind = 'REVERSAL') = (reverses_id IS NOT NULL))
);

CREATE INDEX journals_listing_order_idx ON journals (created_at, id);
CREATE INDEX journals_actor_idx ON journals (actor_id);

CREATE TABLE postings (
    journal_id   uuid     NOT NULL REFERENCES journals (id),
    line_no      smallint NOT NULL CHECK (line_no IN (1, 2)),
    account_id   uuid     NOT NULL REFERENCES accounts (id),
    side         text     NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor bigint   NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (journal_id, line_no),
    UNIQUE (journal_id, side),
    UNIQUE (journal_id, account_id)
);

CREATE INDEX postings_account_idx ON postings (account_id, journal_id);

CREATE TABLE audit_events (
    id            uuid        PRIMARY KEY,
    actor_id      uuid        NOT NULL REFERENCES principals (id),
    action        text        NOT NULL,
    resource_type text        NOT NULL,
    resource_id   uuid        NOT NULL,
    request_id    text        NOT NULL,
    details_json  jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_events_actor_idx ON audit_events (actor_id);
CREATE INDEX audit_events_resource_idx ON audit_events (resource_type, resource_id);

-- The single funding-control account, owned by this migration.
INSERT INTO accounts (id, owner_id, kind, currency, state)
VALUES ('00000000-0000-4000-8000-000000000001', NULL, 'SYSTEM_CONTROL', 'INR', 'ACTIVE');

-- Application role. Login and password are configured outside migrations,
-- so no secret is stored here. Creating it here keeps the grants self-contained.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
        CREATE ROLE ledger_app NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT SELECT ON principals TO ledger_app;

-- Accounts: identity, owner, kind and currency are immutable for the application.
-- Only the two balance columns can change. SELECT ... FOR UPDATE needs an UPDATE grant.
GRANT SELECT, INSERT ON accounts TO ledger_app;
GRANT UPDATE (credit_balance_minor, reserved_minor) ON accounts TO ledger_app;

-- Journal history is append-only for the application.
GRANT SELECT, INSERT ON journals TO ledger_app;
GRANT SELECT, INSERT ON postings TO ledger_app;
GRANT SELECT, INSERT ON audit_events TO ledger_app;

-- Only the migration role may create the SYSTEM_CONTROL account.
-- Row-level security applies to ledger_app; the owner bypasses it.
ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY app_reads_accounts ON accounts FOR SELECT TO ledger_app USING (true);
CREATE POLICY app_updates_accounts ON accounts FOR UPDATE TO ledger_app USING (true) WITH CHECK (true);
CREATE POLICY app_creates_customer_accounts ON accounts FOR INSERT TO ledger_app WITH CHECK (kind = 'CUSTOMER');
