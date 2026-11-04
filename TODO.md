# LedgerCore build TODO

Specification: [docs/specification.md](docs/specification.md) (revision 1.2).
An item is ticked only when `mvn verify` shows its tests passing against real PostgreSQL (Testcontainers, `postgres:17.11`).

## Stage 0 — Design pack revision 1.2

- [x] Narrative, specification rules (UUID lock order, two measurement paths, race-test gates), revision labels and regenerated reading copies, in the separate design pack. Applied on 31 October; the pack's document checker reported no broken links, anchors or overflow.

## Stage 1 — Accounting foundation

Done when: authorization tests and journal/projection tests pass.

- [x] Project skeleton (Spring Boot 4.1.2, Java 21 target) builds; PostgreSQL container starts
- [x] Migrations: principals, accounts, journals, postings, audit events, control-account bootstrap
- [x] Two database roles: migration owner and `ledger_app`; the app role cannot UPDATE or DELETE journals or postings
- [x] Money and identifier parsing rejects decimals, signs, leading zeros, excess digits and wrong currency
- [x] HTTP Basic with three configured identities mapped to seeded principals; current role read from PostgreSQL on every request
- [x] Create account, demo funding, transfer, account, balance and journal-page endpoints
- [x] Transfers lock accounts through one helper in canonical `UUID.toString()` order (race evidence is Stage 2)
- [x] Reconciliation in one read-only REPEATABLE READ snapshot
- [x] Readiness fails without exactly one control account
- [x] Tests: authorization (401/403/404), journal shape, balance projection, reconciliation detects a planted discrepancy

Evidence: `mvn verify`, 44 tests, 0 failures, 0 skipped (MoneyTest 18, HttpSecurityTest 12, LedgerFlowTest 6, DatabaseRoleTest 4, ReconciliationTest 3, ReadinessTest 1).

## Stage 2 — Concurrency and retries

Done when: response-loss and concurrent-spend demonstrations pass.

- [x] `Idempotency-Key` required on every mutation; claim with `ON CONFLICT DO NOTHING`, then a new `SELECT`
- [x] Replay returns the stored status and body; a different body under the same key returns 409; business rejections are stored
- [x] Lock-order helper tests on both sides of hex `8`, equal first halves, opposite-direction transfers
- [x] Test-only transaction gates and the `pg_blocking_pids` observer (never enabled outside tests)
- [x] Gate test: B's transfer is blocked by A's account lock, then sees the reduced balance and is rejected
- [x] Gate test: B's same-key request is blocked by A's uncommitted claim, then replays
- [x] 100 same-key submissions produce exactly one journal (99 replays, one identical body)
- [x] 50 distinct transfers of 3,000 from 10,000 give exactly 3 successes, 47 rejections and a 1,000 balance
- [x] Response lost after commit (HTTP 500 to the client): same-key retry returns the committed journal
- [x] Crash before commit leaves no journal, balance change, key or audit event (events arrive in Stage 4)
- [x] Forbidden requests never reach account locks; a demoted admin's replay is refused
- [x] Unrelated requests stay bounded during a same-key burst

Evidence: `mvn verify`, 65 tests, 0 failures, 0 skipped. ConcurrencyTest and IdempotencyTest also passed three further repeat runs.
Observation recorded by the suite: with a 10-connection pool fully held or queued by 20 same-key requests, an unrelated balance read waited 160–171 ms across four runs. That is a test-harness wait (the gate's hold time plus queueing), not a performance measurement.

## Stage 3 — Reservations and reversals

Done when: state races and compensation preserve every invariant.

- [x] Create, capture and release holds; `GET /v1/holds/{id}`
- [x] Reversal of transfers and captures (ADMIN, reason required, at most once)
- [x] Reconciliation adds reservation and captured-hold checks
- [x] Capture-versus-release race: one terminal state per round (200 rounds), plus a gate test proving release waited on the capture's locks
- [x] Reversal uniqueness under concurrency (10 concurrent attempts: 1 created, 9 ALREADY_REVERSED); insufficient recipient funds rejected, including funds reserved by the recipient's own hold

Evidence: `mvn verify`, 79 tests, 0 failures, 0 skipped. HoldTest and ReversalTest also passed two further repeat runs.
Across three runs the race was won by capture 94, 109 and 124 times out of 200, so both outcomes were exercised.

## Stage 4 — Delivery and access verification

Done when: restart and unauthorized-access tests pass.

- [x] Outbox event committed with every business mutation (and none for a rolled-back change)
- [x] Single poller with bounded backoff, attempt limit and FAILED state; events stranded by a lowered limit become FAILED
- [x] Demonstration consumer with inbox deduplication and a separate service credential
- [x] Admin outbox list and manual retry (idempotent, keeps event ID and total attempts)
- [x] Tests: consumer crash after commit, poller crash after send, consumer outage to FAILED to admin recovery, payload mismatch, credential separation, metrics

Evidence: `mvn verify`, 90 tests, 0 failures, 0 skipped. OutboxTest also passed two further runs on its own.

Finding fixed during this stage: every authenticated request cost about 93 ms, including each outbox delivery. Spring Security re-hashed the configured cost-4 test hashes at its default cost 10 after the first login, because `InMemoryUserDetailsManager` also acts as a password-upgrade service. The user store now exposes lookup only, so the configured hash is the one verified. A regression test checks that. Deliveries in the test profile dropped to 4–8 ms. A real deployment pays its configured cost on every request, including outbox deliveries, which bounds a single poller's delivery rate.

## Stage 5 — Operational demonstration

- [x] README: problem, run instructions, demo walkthrough, results, observations, limitations
- [x] Docker Compose for local PostgreSQL; documented demo credentials. Smoke-tested: `docker compose up`, `scripts/run-local.sh`, `scripts/demo.sh` all worked end to end (transcript: `benchmarks/results/demo-run-20261104.txt`)
- [x] Metrics: replays, rejections, transient failures (lock timeouts, deadlocks), reconciliation runs and failures, failed and pending events, oldest undelivered event, plus HikariCP pool and HTTP timers
- [x] Ledger-path benchmark writing raw results with environment details: `benchmarks/results/ledger-path-20261104T051434Z.json`
- [x] HTTP-path measurement, single sequential client: `benchmarks/results/http-path-20261104T050723Z.csv`. Concurrent HTTP load is still unmeasured.

Evidence: `mvn verify`, 91 tests, 0 failures, 0 skipped, with `fsync=on`.
A first benchmark run was discarded because Testcontainers starts PostgreSQL with `fsync=off` by default; the container now forces durability on.

## Later projects (not started)

- [ ] GraphSched: executor gate, then ML gate (`graphsched.md`)
- [ ] ShardSync: single file first, then folders (`shardsync.md`)
