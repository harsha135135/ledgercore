-- Read-only diagnostic access for IncidentLens. Runs as the schema owner. Contains no password:
-- the login (ledger_diag_reader) is created afterwards by scripts/provision-diagnostics.sh, which
-- refuses to run before this migration.
--
-- Roles:
--   ledger_diagnostics  NOLOGIN. Holds the grants: SELECT on the view, EXECUTE on the function.
--   ledger_diag_owner   NOLOGIN. Holds only pg_read_all_stats and owns the blocker function, so
--                       that SECURITY DEFINER function never runs as the (superuser) schema owner.
--
-- Upgrade note: the two CREATE INDEX statements below block inserts into outbox_events, and so
-- block transfers, while they build. On a large existing table, apply this migration in a quiet period.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'ledger_diagnostics') THEN
        CREATE ROLE ledger_diagnostics NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'ledger_diag_owner') THEN
        CREATE ROLE ledger_diag_owner NOLOGIN;
    END IF;
END
$$;

GRANT pg_read_all_stats TO ledger_diag_owner;

CREATE SCHEMA diagnostics;
REVOKE ALL ON SCHEMA diagnostics FROM PUBLIC;

-- Arrival and publication times for every outbox event. No payload column.
-- created_at is the inserting transaction's start time; published_at is the start time of the
-- single-statement transaction that recorded PENDING -> PUBLISHED. Neither is a commit time.
CREATE VIEW diagnostics.outbox_event_times AS
SELECT id AS event_id,
       type AS event_type,
       state,
       attempts,
       total_attempts,
       created_at,
       published_at,
       next_attempt_at,
       last_error_code
FROM public.outbox_events;

-- Time-window counts over the view use these indexes.
CREATE INDEX outbox_created_at_idx ON public.outbox_events (created_at);
CREATE INDEX outbox_published_at_idx ON public.outbox_events (published_at) WHERE published_at IS NOT NULL;

-- Current lock-wait relationships where the waiting session belongs to ledger_app. The blocker can
-- be any session (another ledger_app session, an operator, a maintenance job); it is labelled only
-- 'ledger_app' or 'other'. No query text, no role names beyond that label, no client addresses.
-- Fixed search_path, schema-qualified names, no dynamic SQL.
CREATE FUNCTION diagnostics.blocking_sessions()
RETURNS TABLE (
    observed_at                 timestamptz,
    blocked_pid                 integer,
    blocked_wait_event_type     text,
    blocked_wait_event          text,
    blocked_lock_wait_started   timestamptz,
    blocker_pid                 integer,
    blocker_role                text,
    blocker_state               text,
    blocker_wait_event_type     text,
    blocker_transaction_started timestamptz
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT pg_catalog.clock_timestamp(),
           blocked.pid,
           blocked.wait_event_type,
           blocked.wait_event,
           (SELECT pg_catalog.min(lock.waitstart)
              FROM pg_catalog.pg_locks AS lock
             WHERE lock.pid = blocked.pid AND NOT lock.granted),
           edge.blocker,
           CASE WHEN blocker.usename = 'ledger_app' THEN 'ledger_app' ELSE 'other' END,
           blocker.state,
           blocker.wait_event_type,
           blocker.xact_start
      FROM pg_catalog.pg_stat_activity AS blocked
     CROSS JOIN LATERAL pg_catalog.unnest(pg_catalog.pg_blocking_pids(blocked.pid)) AS edge (blocker)
      LEFT JOIN pg_catalog.pg_stat_activity AS blocker ON blocker.pid = edge.blocker
     WHERE blocked.usename = 'ledger_app'
       AND blocked.datname = pg_catalog.current_database()
     ORDER BY blocked.pid, edge.blocker
$$;

ALTER FUNCTION diagnostics.blocking_sessions() OWNER TO ledger_diag_owner;
REVOKE ALL ON FUNCTION diagnostics.blocking_sessions() FROM PUBLIC;

GRANT USAGE ON SCHEMA diagnostics TO ledger_diagnostics;
GRANT SELECT ON diagnostics.outbox_event_times TO ledger_diagnostics;
GRANT EXECUTE ON FUNCTION diagnostics.blocking_sessions() TO ledger_diagnostics;
