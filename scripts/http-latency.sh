#!/bin/sh
# HTTP-path latency with one sequential client, against a running local instance.
# Each request opens a new connection (curl is started per request), uses HTTP Basic, and pays
# the configured password-hash cost. Raw per-request timings are written as CSV.
# Usage: scripts/http-latency.sh [requests-per-endpoint]
set -eu
cd "$(dirname "$0")/.."

BASE=${LEDGERCORE_BASE_URL:-http://127.0.0.1:8080}
COUNT=${1:-100}
ADMIN=demo-admin:admin-demo
CUSTOMER_A=customer-a:customer-a-demo
RUN=$(date -u +%Y%m%dT%H%M%SZ)
OUT=benchmarks/results/http-path-$RUN.csv
mkdir -p benchmarks/results

new_account() {
	curl -s -u "$ADMIN" -H 'Content-Type: application/json' -H "Idempotency-Key: bench-acct-$RUN-$1" \
		-X POST "$BASE/v1/accounts" -d "{\"ownerId\":\"$2\"}" | jq -r .accountId
}

SOURCE=$(new_account a a0000000-0000-4000-8000-00000000000a)
DEST=$(new_account b b0000000-0000-4000-8000-00000000000b)
curl -s -o /dev/null -u "$ADMIN" -H 'Content-Type: application/json' -H "Idempotency-Key: bench-fund-$RUN" \
	-X POST "$BASE/v1/demo-funding" \
	-d "{\"destinationAccountId\":\"$SOURCE\",\"amountMinor\":\"100000000\",\"currency\":\"INR\",\"reason\":\"http benchmark\"}"

echo "endpoint,request,http_status,time_total_seconds" > "$OUT"
i=1
while [ "$i" -le "$COUNT" ]; do
	curl -s -o /dev/null -u "$CUSTOMER_A" -w "balance,$i,%{http_code},%{time_total}\n" \
		"$BASE/v1/accounts/$SOURCE/balance" >> "$OUT"
	curl -s -o /dev/null -u "$CUSTOMER_A" -H 'Content-Type: application/json' -H "Idempotency-Key: bench-$RUN-$i" \
		-w "transfer,$i,%{http_code},%{time_total}\n" -X POST "$BASE/v1/transfers" \
		-d "{\"sourceAccountId\":\"$SOURCE\",\"destinationAccountId\":\"$DEST\",\"amountMinor\":\"1\",\"currency\":\"INR\"}" >> "$OUT"
	i=$((i + 1))
done

echo "Raw results: $OUT"
for endpoint in balance transfer; do
	grep "^$endpoint," "$OUT" | cut -d, -f4 | sort -n | awk -v name="$endpoint" '
		{ v[NR] = $1 * 1000 }
		END {
			p50 = v[int((NR + 1) * 0.50)]; p95 = v[int(NR * 0.95)]; p99 = v[int(NR * 0.99)]
			printf "%-8s n=%d  p50=%.1f ms  p95=%.1f ms  p99=%.1f ms  max=%.1f ms\n", name, NR, p50, p95, p99, v[NR]
		}'
done
echo "Non-2xx responses: $(tail -n +2 "$OUT" | cut -d, -f3 | grep -vc '^2' || true)"
