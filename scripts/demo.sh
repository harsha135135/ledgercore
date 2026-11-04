#!/bin/sh
# Walks through the main LedgerCore demonstrations against a running local instance.
# Requires curl and jq, and the demo passwords from .env.example.
set -eu

BASE=${LEDGERCORE_BASE_URL:-http://127.0.0.1:8080}
ADMIN=demo-admin:admin-demo
CUSTOMER_A=customer-a:customer-a-demo
CUSTOMER_A_ID=a0000000-0000-4000-8000-00000000000a
CUSTOMER_B_ID=b0000000-0000-4000-8000-00000000000b
RUN=$(date +%s)

# post USER PATH KEY BODY: prints "<status> <body>"
post() {
	curl -s -u "$1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" \
		-w '\n%{http_code}' -X POST "$BASE$2" -d "$4" | awk 'NR==1{body=$0} NR==2{print $0" "body}'
}

balance() {
	curl -s -u "$1" "$BASE/v1/accounts/$2/balance" | jq -c '{posted: .postedMinor, reserved: .reservedMinor, available: .availableMinor}'
}

step() {
	printf '\n== %s\n' "$1"
}

step "Create two accounts and fund one with 10,000 minor units (Rs 100.00)"
SOURCE=$(post "$ADMIN" /v1/accounts "acct-a-$RUN" "{\"ownerId\":\"$CUSTOMER_A_ID\"}" | cut -d' ' -f2- | jq -r .accountId)
DEST=$(post "$ADMIN" /v1/accounts "acct-b-$RUN" "{\"ownerId\":\"$CUSTOMER_B_ID\"}" | cut -d' ' -f2- | jq -r .accountId)
post "$ADMIN" /v1/demo-funding "fund-$RUN" \
	"{\"destinationAccountId\":\"$SOURCE\",\"amountMinor\":\"10000\",\"currency\":\"INR\",\"reason\":\"demo funding\"}" >/dev/null
echo "source $SOURCE: $(balance "$CUSTOMER_A" "$SOURCE")"

TRANSFER="{\"sourceAccountId\":\"$SOURCE\",\"destinationAccountId\":\"$DEST\",\"amountMinor\":\"3000\",\"currency\":\"INR\"}"

step "Transfer 3,000 with Idempotency-Key transfer-$RUN"
post "$CUSTOMER_A" /v1/transfers "transfer-$RUN" "$TRANSFER"

step "Retry the same request with the same key (as after a lost response): same journal, no second movement"
post "$CUSTOMER_A" /v1/transfers "transfer-$RUN" "$TRANSFER"
echo "source: $(balance "$CUSTOMER_A" "$SOURCE")"

step "Reuse the key with a different amount: 409, nothing moves"
post "$CUSTOMER_A" /v1/transfers "transfer-$RUN" \
	"{\"sourceAccountId\":\"$SOURCE\",\"destinationAccountId\":\"$DEST\",\"amountMinor\":\"9000\",\"currency\":\"INR\"}"

step "Spend more than is available: 422, stored against its key"
post "$CUSTOMER_A" /v1/transfers "too-much-$RUN" \
	"{\"sourceAccountId\":\"$SOURCE\",\"destinationAccountId\":\"$DEST\",\"amountMinor\":\"999999\",\"currency\":\"INR\"}"

step "Hold 2,000, then capture it"
HOLD=$(post "$CUSTOMER_A" /v1/holds "hold-$RUN" \
	"{\"sourceAccountId\":\"$SOURCE\",\"destinationAccountId\":\"$DEST\",\"amountMinor\":\"2000\",\"currency\":\"INR\"}" | cut -d' ' -f2- | jq -r .holdId)
echo "after hold: $(balance "$CUSTOMER_A" "$SOURCE")"
post "$CUSTOMER_A" "/v1/holds/$HOLD/capture" "capture-$RUN" '{}'
echo "after capture: $(balance "$CUSTOMER_A" "$SOURCE")"

step "Customer B cannot see customer A's account (same 404 as a missing one)"
curl -s -u customer-b:customer-b-demo -w '\n' "$BASE/v1/accounts/$SOURCE"

step "Reconciliation (administrator)"
curl -s -u "$ADMIN" "$BASE/v1/admin/reconciliation" | jq -c '{consistent, problems}'

step "Outbox: events are delivered by the poller within a few seconds"
sleep 3
curl -s -u "$ADMIN" "$BASE/v1/admin/outbox?state=PUBLISHED&limit=100" | jq '.items | length' | xargs echo "published events on first page:"
curl -s -u "$ADMIN" "$BASE/v1/admin/outbox?state=FAILED" | jq '.items | length' | xargs echo "failed events:"
