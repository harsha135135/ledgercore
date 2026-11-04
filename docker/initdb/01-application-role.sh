#!/bin/sh
# Runs once, when the database volume is first created. Creates the login the application uses.
# Flyway (running as the schema owner) grants this role its narrow privileges.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v app_password="$LEDGERCORE_DB_APP_PASSWORD" <<'SQL'
CREATE ROLE ledger_app LOGIN PASSWORD :'app_password';
SQL
