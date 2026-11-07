#!/bin/sh
# Creates or updates the read-only diagnostics database login (ledger_diag_reader) in the Compose
# database. Safe to run again; it never drops or rewrites data.
#
# Order:
#   1. Start LedgerCore once, so Flyway applies migration V6 (roles, view, indexes, function).
#   2. Run this script. It stops with a clear message if V6 has not been applied yet.
#
# Reads the env file used by run-local.sh (default .env; override with LEDGERCORE_ENV_FILE), which
# must define LEDGERCORE_DB_OWNER_USER and LEDGERCORE_DIAG_READER_PASSWORD. Set
# LEDGERCORE_COMPOSE_PROJECT to target a Compose project other than the default.
#
# The password reaches PostgreSQL through psql's \getenv, not through a command-line argument.
set -eu
cd "$(dirname "$0")/.."

ENV_FILE=${LEDGERCORE_ENV_FILE:-.env}
case "$ENV_FILE" in
	/*) ;;
	*) ENV_FILE="./$ENV_FILE" ;;
esac
if [ ! -f "$ENV_FILE" ]; then
	echo "Copy .env.example to .env first (or set LEDGERCORE_ENV_FILE)." >&2
	exit 1
fi

set -a
. "$ENV_FILE"
set +a
: "${LEDGERCORE_DB_OWNER_USER:?LEDGERCORE_DB_OWNER_USER is not set in $ENV_FILE}"
: "${LEDGERCORE_DIAG_READER_PASSWORD:?LEDGERCORE_DIAG_READER_PASSWORD is not set in $ENV_FILE}"

{
	printf '%s\n' '\set ON_ERROR_STOP on' 'SET client_min_messages = warning;' \
		'\getenv reader_password LEDGERCORE_DIAG_READER_PASSWORD' \
		'\o /dev/null' \
		"SELECT set_config('ledgercore.diag_reader_password', :'reader_password', false);" \
		'\o'
	cat docker/diagnostics/provision-diagnostics.sql
} | docker compose ${LEDGERCORE_COMPOSE_PROJECT:+-p "$LEDGERCORE_COMPOSE_PROJECT"} --env-file "$ENV_FILE" \
	exec -T -e LEDGERCORE_DIAG_READER_PASSWORD postgres \
	psql -X -q -U "$LEDGERCORE_DB_OWNER_USER" -d ledgercore >/dev/null

echo "ledger_diag_reader is provisioned (read-only, statement_timeout 2s)."
