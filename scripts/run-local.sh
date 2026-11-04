#!/bin/sh
# Starts LedgerCore against the Compose database, reading configuration from .env.
set -eu
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
	echo "Copy .env.example to .env first." >&2
	exit 1
fi

set -a
. ./.env
set +a

JAR=target/ledgercore-0.0.1-SNAPSHOT.jar
if [ ! -f "$JAR" ]; then
	mvn -q -DskipTests package
fi
exec java -jar "$JAR"
