package io.github.harsha135135.ledgercore.diagnostics;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.LockObserver;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The diagnostics database login, tested through that login itself (ledger_diag_reader), with only
 * the settings the login carries. Grants come from migration V6; the login comes from the same
 * provisioning SQL the setup script runs.
 */
class DiagnosticDatabaseAccessTest extends IntegrationTest {

	private static final List<String> BLOCKER_COLUMNS = List.of("observed_at", "blocked_pid",
			"blocked_wait_event_type", "blocked_wait_event", "blocked_lock_wait_started", "blocker_pid",
			"blocker_role", "blocker_state", "blocker_wait_event_type", "blocker_transaction_started");

	@Autowired
	private DataSource applicationPool;

	@BeforeEach
	void provisionLogin() throws Exception {
		PostgresTestDatabase.provisionDiagnosticsReader();
	}

	@Test
	void readerSessionsAreReadOnlyWithATimeoutFromTheLoginItself() throws SQLException {
		try (Connection reader = PostgresTestDatabase.openDiagnosticsReaderConnection();
				Statement statement = reader.createStatement()) {
			assertThat(single(statement, "SHOW default_transaction_read_only")).isEqualTo("on");
			assertThat(single(statement, "SHOW statement_timeout")).isEqualTo("2s");
			assertThatThrownBy(() -> statement.execute("CREATE TEMP TABLE scratch (x integer)"))
				.hasMessageContaining("read-only transaction");

			long started = System.nanoTime();
			assertThatThrownBy(() -> statement.execute("SELECT pg_sleep(5)"))
				.hasMessageContaining("statement timeout");
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
		}
	}

	/** Grants, not only the read-only setting, prevent writes: the session can switch that setting off. */
	@Test
	void readerCannotWriteEvenAfterTurningReadOnlyOff() throws SQLException {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		try (Connection reader = PostgresTestDatabase.openDiagnosticsReaderConnection();
				Statement statement = reader.createStatement()) {
			statement.execute("SET default_transaction_read_only = off");
			assertPermissionDenied(statement, "UPDATE accounts SET credit_balance_minor = 0 WHERE id = '" + account + "'");
			assertPermissionDenied(statement, "UPDATE outbox_events SET state = 'FAILED'");
			assertPermissionDenied(statement, "DELETE FROM journals");
			assertPermissionDenied(statement,
					"INSERT INTO diagnostics.outbox_event_times (event_id) VALUES (gen_random_uuid())");
			assertPermissionDenied(statement, "CREATE TABLE diagnostics.scratch (x integer)");
			assertPermissionDenied(statement, "CREATE TABLE public.scratch (x integer)");
		}
		assertThat(postedBalance(account)).isEqualTo(1_000);
	}

	@Test
	void readerCannotReadLedgerTablesOrEventPayloads() throws SQLException {
		try (Connection reader = PostgresTestDatabase.openDiagnosticsReaderConnection();
				Statement statement = reader.createStatement()) {
			for (String table : List.of("outbox_events", "accounts", "journals", "postings", "audit_events",
					"principals", "idempotency_requests", "holds", "consumer_inbox", "consumer_event_counts")) {
				assertPermissionDenied(statement, "SELECT * FROM public." + table + " LIMIT 1");
			}
			assertThat(column(statement, "SELECT column_name FROM information_schema.columns "
					+ "WHERE table_schema = 'diagnostics' AND table_name = 'outbox_event_times'"))
				.containsExactlyInAnyOrder("event_id", "event_type", "state", "attempts", "total_attempts",
						"created_at", "published_at", "next_attempt_at", "last_error_code");
		}
	}

	/**
	 * Among application-defined functions (outside pg_catalog and information_schema), the login can
	 * execute exactly one. Built-in functions granted to PUBLIC stay callable, bounded by the
	 * read-only setting, the grants and the statement timeout.
	 */
	@Test
	void onlyTheApprovedFunctionIsExecutableAndItDoesNotRunAsSuperuser() throws SQLException {
		try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
			assertThat(column(statement, """
					SELECT p.oid::regprocedure::text
					FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
					WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
					  AND has_function_privilege('ledger_diag_reader', p.oid, 'EXECUTE')
					ORDER BY 1""")).containsExactly("diagnostics.blocking_sessions()");

			assertThat(single(statement, """
					SELECT p.prosecdef || ' ' || pg_get_userbyid(p.proowner) || ' ' || array_to_string(p.proconfig, ';')
					FROM pg_proc p WHERE p.oid = 'diagnostics.blocking_sessions()'::regprocedure"""))
				.isEqualTo("true ledger_diag_owner search_path=pg_catalog, pg_temp");
			assertThat(single(statement,
					"SELECT rolsuper || ' ' || rolcanlogin FROM pg_roles WHERE rolname = 'ledger_diag_owner'"))
				.isEqualTo("false false");
			assertThat(column(statement, memberships("ledger_diag_owner"))).containsExactly("pg_read_all_stats");
			assertThat(single(statement, "SELECT rolsuper || ' ' || rolcanlogin || ' ' || rolinherit "
					+ "FROM pg_roles WHERE rolname = 'ledger_diag_reader'")).isEqualTo("false true true");
			assertThat(column(statement, memberships("ledger_diag_reader"))).containsExactly("ledger_diagnostics");
		}
		try (Connection app = PostgresTestDatabase.openAppConnection(); Statement statement = app.createStatement()) {
			assertPermissionDenied(statement, "SELECT * FROM diagnostics.blocking_sessions()");
		}
	}

	/** D2: the blocked session is ledger_app; the blocker is reported even when it is another role. */
	@Test
	void blockerFunctionReportsABlockerThatIsNotTheApplication() throws Exception {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		try (Connection holder = PostgresTestDatabase.openOwnerConnection();
				Connection waiter = PostgresTestDatabase.openAppConnection()) {
			BlockerRow edge = observeBlock(holder, waiter, account);
			assertThat(edge.blockerRole()).isEqualTo("other");
			assertThat(edge.blockerState()).isEqualTo("idle in transaction");
		}
	}

	@Test
	void blockerFunctionLabelsAnApplicationBlocker() throws Exception {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		try (Connection holder = PostgresTestDatabase.openAppConnection();
				Connection waiter = PostgresTestDatabase.openAppConnection()) {
			BlockerRow edge = observeBlock(holder, waiter, account);
			assertThat(edge.blockerRole()).isEqualTo("ledger_app");
		}
	}

	@Test
	void blockerFunctionReturnsOnlyTheApprovedColumns() throws SQLException {
		try (Connection reader = PostgresTestDatabase.openDiagnosticsReaderConnection();
				Statement statement = reader.createStatement();
				ResultSet rows = statement.executeQuery("SELECT * FROM diagnostics.blocking_sessions()")) {
			ResultSetMetaData meta = rows.getMetaData();
			List<String> columns = new ArrayList<>();
			for (int i = 1; i <= meta.getColumnCount(); i++) {
				columns.add(meta.getColumnName(i));
			}
			assertThat(columns).containsExactlyElementsOf(BLOCKER_COLUMNS);
		}
	}

	/**
	 * D3: arrival buckets are final only after the longest ledger_app transaction can end. The pool's
	 * own connections carry transaction_timeout=10s; a transaction running past it is terminated.
	 */
	@Test
	void applicationTransactionsEndAtTheTenSecondTransactionTimeout() throws SQLException {
		try (Connection pooled = applicationPool.getConnection(); Statement statement = pooled.createStatement()) {
			assertThat(single(statement, "SELECT current_user")).isEqualTo(PostgresTestDatabase.APP_USER);
			assertThat(single(statement, "SHOW transaction_timeout")).isEqualTo("10s");
			pooled.setAutoCommit(false);
			long started = System.nanoTime();
			// Each statement stays under the 5 s statement timeout; together they pass 10 s.
			assertThatThrownBy(() -> {
				for (int i = 0; i < 4; i++) {
					statement.execute("SELECT pg_sleep(4)");
				}
			}).isInstanceOf(SQLException.class).hasMessageContaining("transaction timeout");
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(9_500),
					Duration.ofSeconds(13));
		}
	}

	/**
	 * Spec section 10: the largest allowed window (30 minutes) must stay cheap before it is polled
	 * repeatedly. 24 hours of background events at 2/s plus a 30-minute burst at 50/s, the highest
	 * arrival rate the evaluation workload may use.
	 */
	@Test
	void thirtyMinuteWindowCountsStayFastAtTheHighestEvaluationRate() throws SQLException {
		Instant now = Instant.now();
		try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
			insertSyntheticEvents(statement, now.minus(Duration.ofHours(24)), 172_800, 500);
			insertSyntheticEvents(statement, now.minus(Duration.ofMinutes(30)), 90_000, 20);
			statement.execute("ANALYZE outbox_events");
		}
		try (Connection reader = PostgresTestDatabase.openDiagnosticsReaderConnection()) {
			Instant start = now.minus(Duration.ofMinutes(30));
			for (String column : List.of("created_at", "published_at")) {
				String sql = "SELECT date_bin('10 seconds', " + column + ", ?) AS bucket, count(*) "
						+ "FROM diagnostics.outbox_event_times WHERE " + column + " >= ? AND " + column + " < ? "
						+ "GROUP BY bucket ORDER BY bucket";
				long started = System.nanoTime();
				long rows = countRows(reader, sql, start, start, now);
				Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
				assertThat(rows).as(column + " buckets").isGreaterThan(170);
				assertThat(elapsed).as(column + " query time").isLessThan(Duration.ofSeconds(1));
			}
			// A narrow window in a large table is answered from the index, not a full scan.
			String plan = explain(reader, "SELECT count(*) FROM diagnostics.outbox_event_times "
					+ "WHERE created_at >= now() - interval '6 hours' AND created_at < now() - interval '5 hours 59 minutes'");
			assertThat(plan).contains("outbox_created_at_idx");
		}
		finally {
			try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
				statement.execute("DELETE FROM outbox_events WHERE type = 'diagnostics.synthetic'");
				statement.execute("DELETE FROM audit_events WHERE action = 'DIAGNOSTICS_SYNTHETIC'");
			}
		}
	}

	/** D5: an existing database without V6 is refused, then upgraded without losing data; reruns are safe. */
	@Test
	void provisioningUpgradesAnExistingDatabaseAndRefusesBeforeV6() throws Exception {
		String database = "upgrade_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
			statement.execute("CREATE DATABASE " + database);
		}
		UUID eventId = UUID.randomUUID();
		try {
			migrate(database, "5");
			try (Connection owner = PostgresTestDatabase.openOwnerConnection(database)) {
				assertThatThrownBy(() -> PostgresTestDatabase.runProvisioning(owner, PostgresTestDatabase.DIAG_READER_PASSWORD))
					.hasMessageContaining("Migration V6 (diagnostics) has not been applied");
				insertEvent(owner, eventId);
			}
			migrate(database, "latest");
			try (Connection owner = PostgresTestDatabase.openOwnerConnection(database)) {
				PostgresTestDatabase.runProvisioning(owner, PostgresTestDatabase.DIAG_READER_PASSWORD);
				PostgresTestDatabase.runProvisioning(owner, PostgresTestDatabase.DIAG_READER_PASSWORD);
				assertThat(single(owner.createStatement(), "SELECT payload_json FROM outbox_events WHERE id = '" + eventId + "'"))
					.isEqualTo("{\"note\":\"existing\"}");
			}
			String url = PostgresTestDatabase.jdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
			try (Connection reader = DriverManager.getConnection(url, PostgresTestDatabase.DIAG_READER_USER,
					PostgresTestDatabase.DIAG_READER_PASSWORD); Statement statement = reader.createStatement()) {
				assertThat(single(statement, "SELECT event_id || ' ' || state FROM diagnostics.outbox_event_times"))
					.isEqualTo(eventId + " PENDING");
			}
		}
		finally {
			try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
				statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
			}
		}
	}

	/** Holds a row lock on {@code holder}, makes {@code waiter} (ledger_app) wait for it, and reads the edge. */
	private BlockerRow observeBlock(Connection holder, Connection waiter, UUID account) throws Exception {
		holder.setAutoCommit(false);
		waiter.setAutoCommit(false);
		long holderPid = pid(holder);
		long waiterPid = pid(waiter);
		lockAccount(holder, account);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (LockObserver observer = LockObserver.open()) {
			Future<?> waiting = executor.submit(() -> {
				try (Statement statement = waiter.createStatement()) {
					statement.execute("SET LOCAL lock_timeout = '5s'");
					lockAccount(waiter, account);
				}
				return null;
			});
			observer.awaitBlockedBy(holderPid, 1, Duration.ofSeconds(3));
			BlockerRow edge = blockerRows().stream()
				.filter(row -> row.blockedPid() == waiterPid)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No blocker row for the waiting ledger_app session"));
			assertThat(edge.blockerPid()).isEqualTo(holderPid);
			assertThat(edge.blockedWaitEventType()).isEqualTo("Lock");
			assertThat(edge.lockWaitStarted()).isNotNull();
			assertThat(edge.blockerTransactionStarted()).isNotNull();
			holder.rollback();
			waiting.get(5, TimeUnit.SECONDS);
			waiter.rollback();
			return edge;
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static List<BlockerRow> blockerRows() throws SQLException {
		List<BlockerRow> rows = new ArrayList<>();
		try (Connection reader = PostgresTestDatabase.openDiagnosticsReaderConnection();
				Statement statement = reader.createStatement();
				ResultSet rs = statement.executeQuery("SELECT * FROM diagnostics.blocking_sessions()")) {
			while (rs.next()) {
				rows.add(new BlockerRow(rs.getLong("blocked_pid"), rs.getString("blocked_wait_event_type"),
						rs.getTimestamp("blocked_lock_wait_started"), rs.getLong("blocker_pid"),
						rs.getString("blocker_role"), rs.getString("blocker_state"),
						rs.getTimestamp("blocker_transaction_started")));
			}
		}
		return rows;
	}

	private static void lockAccount(Connection connection, UUID account) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("SELECT id FROM accounts WHERE id = '" + account + "' FOR UPDATE");
		}
	}

	private static long pid(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			return Long.parseLong(single(statement, "SELECT pg_backend_pid()"));
		}
	}

	/** Inserts already-published synthetic events, one every {@code spacingMillis}, starting at {@code from}. */
	private static void insertSyntheticEvents(Statement statement, Instant from, int count, int spacingMillis)
			throws SQLException {
		statement.execute("""
				WITH audit AS (
				    INSERT INTO audit_events (id, actor_id, action, resource_type, resource_id, request_id, details_json, created_at)
				    SELECT gen_random_uuid(), '%s', 'DIAGNOSTICS_SYNTHETIC', 'TEST', gen_random_uuid(), 'diagnostics-test',
				           '{}'::jsonb, timestamptz '%s' + (i * interval '%d milliseconds')
				    FROM generate_series(0, %d) AS i
				    RETURNING id, created_at)
				INSERT INTO outbox_events (id, audit_event_id, type, schema_version, payload_json, state, created_at,
				                           attempts, total_attempts, next_attempt_at, published_at)
				SELECT gen_random_uuid(), audit.id, 'diagnostics.synthetic', 1, '{}', 'PUBLISHED', audit.created_at,
				       1, 1, audit.created_at, audit.created_at + interval '150 milliseconds'
				FROM audit""".formatted(TestUsers.ADMIN_ID, from, spacingMillis, count - 1));
	}

	private static void insertEvent(Connection owner, UUID eventId) throws SQLException {
		UUID auditId = UUID.randomUUID();
		try (PreparedStatement audit = owner.prepareStatement("INSERT INTO audit_events (id, actor_id, action, "
				+ "resource_type, resource_id, request_id, details_json) VALUES (?, ?, 'TEST', 'TEST', ?, 'upgrade-test', '{}')");
				PreparedStatement event = owner.prepareStatement("INSERT INTO outbox_events (id, audit_event_id, type, "
						+ "schema_version, payload_json, state) VALUES (?, ?, 'test.event', 1, ?, 'PENDING')")) {
			audit.setObject(1, auditId);
			audit.setObject(2, TestUsers.ADMIN_ID);
			audit.setObject(3, UUID.randomUUID());
			audit.execute();
			event.setObject(1, eventId);
			event.setObject(2, auditId);
			event.setString(3, "{\"note\":\"existing\"}");
			event.execute();
		}
	}

	private static void migrate(String database, String target) {
		String url = PostgresTestDatabase.jdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
		Flyway.configure()
			.dataSource(url, PostgresTestDatabase.ownerUser(), PostgresTestDatabase.ownerPassword())
			.target(target)
			.load()
			.migrate();
	}

	private static long countRows(Connection connection, String sql, Instant origin, Instant start, Instant end)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setTimestamp(1, Timestamp.from(origin));
			statement.setTimestamp(2, Timestamp.from(start));
			statement.setTimestamp(3, Timestamp.from(end));
			long rows = 0;
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					rows++;
				}
			}
			return rows;
		}
	}

	private static String explain(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			return String.join("\n", column(statement, "EXPLAIN " + sql));
		}
	}

	private static String memberships(String role) {
		return "SELECT r.rolname FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid "
				+ "JOIN pg_roles u ON u.oid = m.member WHERE u.rolname = '" + role + "' ORDER BY 1";
	}

	private static String single(Statement statement, String sql) throws SQLException {
		List<String> values = column(statement, sql);
		assertThat(values).as(sql).hasSize(1);
		return values.get(0);
	}

	private static List<String> column(Statement statement, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (ResultSet rs = statement.executeQuery(sql)) {
			while (rs.next()) {
				values.add(rs.getString(1));
			}
		}
		return values;
	}

	private static void assertPermissionDenied(Statement statement, String sql) {
		assertThatThrownBy(() -> statement.execute(sql)).as(sql)
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("permission denied");
	}

	private record BlockerRow(long blockedPid, String blockedWaitEventType, Timestamp lockWaitStarted, long blockerPid,
			String blockerRole, String blockerState, Timestamp blockerTransactionStarted) {
	}

}
