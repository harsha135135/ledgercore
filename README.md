# LedgerCore

A double-entry ledger service in Java, Spring Boot and PostgreSQL. It moves simulated money between accounts and stays correct when requests are retried, race each other or crash partway.

This is an educational project with one currency (INR) and synthetic funding. It has no bank connections and makes no regulatory claims. The specification it implements is [docs/specification.md](docs/specification.md) (revision 1.2).

[![CI](https://github.com/harsha135135/ledgercore/actions/workflows/ci.yml/badge.svg)](https://github.com/harsha135135/ledgercore/actions/workflows/ci.yml)

## The problem

A money-moving API fails in ways a CRUD app can ignore:

| Failure | Without protection |
|---|---|
| Lost response | The transfer commits, the reply is lost, the client retries, and money moves twice |
| Concurrent spending | Two requests both see ₹100 available and both spend it |
| Overwritten balance | A balance column shows a number but cannot explain it |
| Separate event publish | A commit and a message publish cannot be atomic, so an event goes missing or announces something that rolled back |

## How it is handled

| Guarantee | Mechanism | Proven by |
|---|---|---|
| A retried request moves money at most once | `Idempotency-Key` claimed with `INSERT … ON CONFLICT DO NOTHING` inside the business transaction; the stored response is replayed | `IdempotencyTest`, `ConcurrencyTest` |
| Concurrent spends never overdraw | Accounts locked one by one in a single canonical order, funds checked only on locked rows | `ConcurrencyTest` |
| Every balance is explainable | Each movement is one journal with exactly one debit and one equal credit; balances are a projection updated in the same transaction | `LedgerFlowTest`, `ReconciliationTest` |
| History cannot be rewritten by the app | The application's database role can insert and read journals, postings and audit events, but never update or delete them | `DatabaseRoleTest` |
| Events match committed changes | Each mutation writes an outbox event in its own transaction; a single poller delivers at least once; the consumer deduplicates by event ID | `OutboxTest` |
| Forbidden callers never touch locks | Immutable ownership is checked with ordinary reads before any key claim or lock | `ConcurrencyTest`, `HttpSecurityTest` |

## Run the tests

Requires Docker and a JDK 21 or newer. The build targets Java 21; it was developed and tested on OpenJDK 25.0.2.

```sh
mvn verify
```

The suite starts one PostgreSQL 17.11 container through Testcontainers, with `fsync` and `synchronous_commit` on. It runs 91 tests, all against that real database, plus real HTTP for the contract and security tests. Test logins use BCrypt cost 4 to keep the suite fast; correctness does not depend on the cost.

## Run it locally

```sh
cp .env.example .env                  # demo credentials; change the passwords
docker compose up -d --wait           # PostgreSQL 17.11 on 127.0.0.1:55432
./scripts/run-local.sh                # builds if needed, then starts on 127.0.0.1:8080
./scripts/demo.sh                     # needs curl and jq
```

The demo creates accounts, funds one, and then shows these cases:

- a transfer, and a same-key retry that returns the same journal
- a key reused with a different body (409)
- an overspend (422)
- a hold, then its capture
- an account another customer cannot see (404)
- a clean reconciliation
- outbox events being published

Stop with Ctrl+C, then run `docker compose down -v`.

Demo logins are `customer-a`, `customer-b` and `demo-admin`, with the passwords listed in `.env.example`. The service binds to loopback only and uses stateless HTTP Basic for a CLI or API client.

### Read-only diagnostics (optional)

These additions let a separate diagnostic tool, IncidentLens, observe LedgerCore without ledger access. None of them changes how money moves.

- **Prometheus export:** `/actuator/prometheus`, with fixed latency buckets. Only the `diagnostics-reader` login (`LEDGERCORE_DIAGNOSTICS_PASSWORD_HASH`) can read it; that login gets 403 everywhere else.
- **Diagnostic records:** set `LEDGERCORE_LOG_FILE` and `LEDGERCORE_DIAGNOSTICS_LOG_LEVEL=INFO` to get a JSON log file (Elastic Common Schema, UTC). It holds one record per outbox delivery attempt and per non-empty polling pass, plus a startup configuration record. Records carry IDs, times and results, never event payloads.
- **Database access:**
  - **What V6 adds:** migration V6 adds the payload-free view `diagnostics.outbox_event_times`, two indexes, and `diagnostics.blocking_sessions()`.
  - **The login:** `ledger_diag_reader` is read-only, with a 2 s statement timeout. It is created after V6 with:

    ```sh
    ./scripts/provision-diagnostics.sh    # needs LEDGERCORE_DIAG_READER_PASSWORD in .env; safe to rerun
    ```

  - **Large existing databases:** V6's index builds block outbox inserts while they run, so upgrade a large database in a quiet period.

## API

All mutations require an `Idempotency-Key` header. Money is a decimal string of minor units (`"12500"` means ₹125.00).

| Endpoint | Who | Result |
|---|---|---|
| `POST /v1/accounts` | ADMIN | New customer account with zero balances |
| `POST /v1/demo-funding` | ADMIN | Funding journal (debits the system control account) |
| `POST /v1/transfers` | Source owner | Transfer journal |
| `POST /v1/holds` | Source owner | ACTIVE hold reserving funds |
| `POST /v1/holds/{id}/capture` | Source owner or ADMIN | Capture journal |
| `POST /v1/holds/{id}/release` | Source owner or ADMIN | RELEASED hold |
| `POST /v1/journals/{id}/reversal` | ADMIN | Compensating REVERSAL journal |
| `GET /v1/accounts/{id}`, `/balance`, `/journals` | Owner or ADMIN | Account, balance, paged journal entries |
| `GET /v1/holds/{id}` | Source owner or ADMIN | Hold |
| `GET /v1/admin/reconciliation` | ADMIN | Consistency report from one snapshot |
| `GET /v1/admin/outbox?state=FAILED` | ADMIN | Event metadata page |
| `POST /v1/admin/outbox/{id}/retry` | ADMIN | Requeue a FAILED event |

A missing resource and one you may not see both return the same 404. Errors always carry a stable `code`, a safe `message` and the `requestId` (also in the `X-Request-Id` header).

## How a transfer runs

1. Authenticate; read the caller's current role from PostgreSQL.
2. Check that the caller owns the source account (an ordinary read: ownership is immutable). Forbidden requests stop here.
3. Start a READ COMMITTED transaction and re-read the role.
4. Claim the idempotency key. If another transaction holds it, wait; then a new `SELECT` returns the stored result (same request) or 409 (different request).
5. Lock both accounts through `AccountLocks`, ordered by canonical `UUID.toString()`. `UUID.compareTo` is avoided because it compares signed halves.
6. Check state and available funds on the locked rows.
7. Write the journal and two postings, both balances, the audit event and the outbox event. Store the response against the key, then commit.
8. Respond. Separately, the poller delivers the event.

Business rejections such as insufficient funds are stored against their key too, so a retry gets the same answer. Unexpected errors roll everything back and are not stored.

## Results

### Correctness

All from `mvn verify` against real PostgreSQL. Race tests prove the race happened: test-only gates pause transaction A, and an observer connection confirms through `pg_blocking_pids` that transaction B is blocked by A before A is released.

| Scenario | Outcome |
|---|---|
| 100 concurrent submissions of one key | 1 journal; 99 replays with byte-identical bodies |
| 50 concurrent transfers of 3,000 from 10,000 | Exactly 3 succeeded, 47 rejected for insufficient funds, 1,000 left |
| Second transfer behind the first one's lock (gate) | Blocked, then saw the reduced balance and was rejected |
| Same-key request behind an uncommitted claim (gate) | Blocked, then replayed the stored result |
| Opposite-direction transfers on accounts either side of hex `8` | No deadlock: gate showed B waiting on its first lock; an 80-transfer burst completed with no `40P01` |
| Response lost after commit (HTTP 500 to the client) | Same-key retry returned 201 with the committed journal ID; one journal |
| Crash before commit | No journal, balance change, key, audit event or outbox event; the retry then ran normally |
| Same key, different body | 409; no second movement |
| Forbidden transfer while the victim's account is locked | 404 without waiting; no backend blocked; no key claimed |
| Demoted administrator replays a funding key | 403; stored journal ID not disclosed |
| Capture racing release, 200 rounds | Exactly one terminal state every round (capture won 94, 109, 110 and 124 of 200 across four runs) |
| 10 concurrent reversals of one journal | 1 created, 9 `ALREADY_REVERSED` |
| Consumer crash after commit, before acknowledging | Redelivered; effect applied once |
| Consumer outage | FAILED after 8 attempts; admin retry kept the event ID and total attempts; published on attempt 9; applied once |
| Reconciliation after every concurrent test | Consistent, including all balances summing to zero |

### Ledger-path benchmark

Command: `mvn test -Dexcluded.test.groups=none -Dtest=LedgerPathBenchmark`
Raw results: `benchmarks/results/ledger-path-20261104T051434Z.json`

This path calls `LedgerService.transfer` directly with an authenticated principal. It includes the role and ownership reads, pool acquisition and the whole transaction. It excludes HTTP and password verification. Figures are medians of 3 runs of 3,000 transfers each, after 500 warm-up transfers, with 0 errors in every run.

| Workload | Concurrency 1 | 4 | 8 | 16 |
|---|---|---|---|---|
| 64 sources (spread): transfers/s | 349 | 888 | 1,221 | 1,330 |
| Spread: p95 / p99 ms | 3.2 / 3.8 | 5.5 / 6.7 | 9.1 / 12.0 | 33.9 / 58.3 |
| One source (hot): transfers/s | 350 | 510 | 460 | 481 |
| Hot: p95 / p99 ms | 3.1 / 3.6 | 15.0 / 20.7 | 80.3 / 164.3 | 152.3 / 275.3 |

Environment: Apple M4 Pro (12 cores, macOS 26.5.2), OpenJDK 25.0.2, connection pool of 10. PostgreSQL 17.11 ran in Testcontainers inside the Docker Desktop VM (12 CPUs, 7.9 GiB), with `fsync=on` and `synchronous_commit=on`.

### HTTP path

Command: `./scripts/http-latency.sh 200` against the local instance
Raw results: `benchmarks/results/http-path-20261104T050723Z.csv`

One sequential client, a new connection per request, BCrypt cost 10, 200 requests per endpoint, 0 non-2xx responses.

| Endpoint | p50 | p95 | p99 |
|---|---|---|---|
| `GET /v1/accounts/{id}/balance` | 93.5 ms | 95.9 ms | 96.6 ms |
| `POST /v1/transfers` | 103.0 ms | 106.3 ms | 107.9 ms |

The median BCrypt verification on the same machine was 89.4 ms at cost 10 and 1.7 ms at cost 4.

### Concurrent HTTP load

Command: `java scripts/HttpLoad.java` against the local instance
Raw results: `benchmarks/results/http-load-20261104T060916Z.{csv,json}` (console log alongside)

Prediction recorded before the run: at about 89 ms of CPU per password check, throughput should plateau below 12 / 0.089 = about 135 requests/s, probably near 100. Medians of 3 runs, after 100 warm-up requests. Runs had max(100, 50 × concurrency) requests each, over HTTP/1.1 with keep-alive connections reused, at BCrypt cost 10. There were 0 non-2xx responses in every run.

| Concurrency | Balance req/s | Balance p50 / p99 | Transfer req/s | Transfer p50 / p99 |
|---|---|---|---|---|
| 1 | 10.7 | 93.7 / 95.9 ms | 10.0 | 100.1 / 105.3 ms |
| 4 | 43.2 | 92.7 / 94.5 ms | 39.9 | 100.4 / 105.9 ms |
| 8 | 85.5 | 93.5 / 99.8 ms | 78.2 | 101.3 / 120.2 ms |
| 16 | 119.1 | 134.0 / 183.9 ms | 103.6 | 150.6 / 227.4 ms |
| 32 | 120.1 | 264.9 / 370.4 ms | 105.3 | 297.0 / 526.2 ms |

The prediction held. Throughput scaled linearly to 8 clients and then plateaued at about 120 balance reads or 105 transfers per second. Past that point extra clients only add queueing: p50 latency doubles from 16 to 32 clients. The client, the server and the database VM all shared the same 12 cores.

**Outbox under load.** The transfer runs left 8,680 events pending. Over the next 15 minutes the single poller delivered them at a steady 6.8 events per second, and 2,545 were still pending when sampling stopped. No event reached FAILED. This run's raw JSON shows `drainSeconds: -1` and `averageDrainRatePerSecond: 0.0`, because the backlog did not drain within the window and the script's earlier summary logic had no rate for that case. The 6.8 per second comes from the JSON's own samples: (8,680 − 2,545) / 900.9 s. The script now reports the window rate directly.

## Observations

- **One busy account caps throughput.** The hot workload stops scaling after 4 threads, at about 500 transfers per second, while its tail latency grows. Every transfer queues for the same row lock. The spread workload keeps scaling until the 10-connection pool saturates; at 16 threads its p95 jumps to about 34 ms because of pool queueing.
- **Password hashing dominates the HTTP path.** At cost 10, about 89 ms of a 94 ms balance read is password verification. That is why ledger-path and HTTP-path figures answer different questions and are reported separately.
- **Spring Security was silently raising the hash cost.** `InMemoryUserDetailsManager` also acts as a password-upgrade service, so after the first login Spring re-hashed the configured cost-4 test hashes at cost 10. Every request then paid about 93 ms, including each outbox delivery. The user store now exposes lookup only, and `HttpSecurityTest` guards this. Deliveries in the test profile dropped to 4–8 ms.
- **Testcontainers disables fsync by default.** The first benchmark run recorded `fsync=off` in its environment block, so it was discarded and the container now starts with durability on. Single-thread throughput was slightly higher with fsync on. That suggests flushes inside the Docker Desktop VM are cheap, so commit cost here probably understates a real server.
- **The outbox falls far behind under load (measured).** Each delivery authenticates with the publisher's service credential, so it pays about 89 ms of BCrypt on the consumer side. The poller also waits 1 second after each batch of 20. That gives about 20 / (20 × 0.09 s + 1 s) ≈ 7 events per second, and 6.8 per second was measured while 105 transfers per second were arriving. A sustained load therefore grows the backlog without limit. The remedies are cheaper service authentication (a short-lived token or mutual TLS), polling again immediately when a batch was full, or more than one poller with a claim protocol.
- **Same-key waiters hold pool connections.** With 20 same-key requests holding or queued for all 10 connections, an unrelated balance read waited 160–171 ms behind them in the tests. That is a test-harness wait, not a performance figure, but it shows retries are not free.

## Limitations

- One currency, synthetic funding, full holds only, no expiry, no account closure.
- One PostgreSQL database and one outbox poller; no high availability or leased multi-poller claims.
- Demo authentication only: stateless HTTP Basic with users from configuration, on loopback, no TLS. Each request pays the configured password-hash cost, and so does each outbox delivery to the consumer.
- The consumer is a demonstration running in the same process.
- Crashes are modelled by exceptions at named checkpoints that roll the transaction back, not by killing processes. Power-loss behaviour is not tested.
- Measurements come from one Mac, with the load client, application and PostgreSQL (inside the Docker Desktop VM) sharing its cores. They are not capacity claims.
- The M4 Pro has 8 performance and 4 efficiency cores, and macOS chose where threads ran. Results at higher concurrency may include efficiency-core time.
- Lock waits are counted only when they time out (`ledgercore.transient.failures`). There is no lock-wait duration metric.
- Observability is metrics plus request and event IDs in every log line, and the optional read-only diagnostics above. There is no distributed tracing.
- Any ledger login, including a customer, can read `/actuator/metrics`. The narrower diagnostics login exists for the Prometheus export.
- The specification's separate backup-and-restore demonstration has not been done.

## Layout

```text
src/main/java/io/github/harsha135135/ledgercore/
  api/          HTTP plumbing: request IDs, body limit, error format
  security/     HTTP Basic, principal mapping, permission rules
  ledger/       accounts, funding, transfers, reversals, reconciliation, lock order
  hold/         holds: create, capture, release
  idempotency/  key claim, replay, request hashing
  outbox/       business events, poller, publisher, admin recovery, metrics
  consumer/     demonstration consumer with inbox deduplication
  database/     transaction templates and test checkpoints
src/main/resources/db/migration/   V1 core ledger ... V5 outbox
src/test/java/.../support/         container, gates, lock observer, HTTP client
benchmarks/results/                raw measurement files
```
