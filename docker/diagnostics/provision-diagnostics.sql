-- Creates or updates the read-only diagnostics login, ledger_diag_reader. Safe to run again: it
-- never drops anything and never touches ledger data.
--
-- Run as the schema owner, after migration V6. The caller first puts the login password in a
-- session setting, so the password never appears in this file or on a command line:
--     SELECT set_config('ledgercore.diag_reader_password', '<password>', false);
-- scripts/provision-diagnostics.sh does this through psql; the tests do it with a bind parameter.

DO $provision$
DECLARE
    reader_password text := current_setting('ledgercore.diag_reader_password', true);
BEGIN
    IF pg_catalog.to_regprocedure('diagnostics.blocking_sessions()') IS NULL THEN
        RAISE EXCEPTION 'Migration V6 (diagnostics) has not been applied. Start LedgerCore once so Flyway migrates the database, then run this again.';
    END IF;
    IF reader_password IS NULL OR length(reader_password) < 16 THEN
        RAISE EXCEPTION 'Set ledgercore.diag_reader_password to a password of at least 16 characters first.';
    END IF;
    -- CREATE/ALTER ROLE cannot take bind parameters, so the password is quoted with %L.
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'ledger_diag_reader') THEN
        EXECUTE format('CREATE ROLE ledger_diag_reader LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE '
                       'NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4 PASSWORD %L', reader_password);
    ELSE
        EXECUTE format('ALTER ROLE ledger_diag_reader LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE '
                       'NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4 PASSWORD %L', reader_password);
    END IF;
END
$provision$;

-- Settings belong on the login itself: membership in ledger_diagnostics grants privileges, not
-- session settings. IncidentLens also opens every transaction READ ONLY with its own timeout.
ALTER ROLE ledger_diag_reader SET default_transaction_read_only = on;
ALTER ROLE ledger_diag_reader SET statement_timeout = '2s';
ALTER ROLE ledger_diag_reader SET lock_timeout = '1s';
ALTER ROLE ledger_diag_reader SET idle_in_transaction_session_timeout = '10s';

GRANT ledger_diagnostics TO ledger_diag_reader;

-- Clear the password from this session.
SELECT set_config('ledgercore.diag_reader_password', '', false);
