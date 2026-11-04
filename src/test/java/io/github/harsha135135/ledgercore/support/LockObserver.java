package io.github.harsha135135.ledgercore.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Observes lock waits from a dedicated connection outside the application pool, so observation
 * never competes with the transactions being observed. Evidence of overlap means a backend waits
 * on a heavyweight lock ({@code wait_event_type = 'Lock'}) and {@code pg_blocking_pids} names the
 * expected blocker. Merely "waiting" is not enough.
 */
public final class LockObserver implements AutoCloseable {

	private static final String BLOCKED_BY = """
			SELECT pid FROM pg_stat_activity
			WHERE wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))
			ORDER BY pid
			""";

	private final Connection connection;

	private LockObserver(Connection connection) {
		this.connection = connection;
	}

	public static LockObserver open() throws SQLException {
		return new LockObserver(PostgresTestDatabase.openOwnerConnection());
	}

	/** Backends currently blocked by {@code blockerPid}. */
	public List<Long> blockedBy(long blockerPid) throws SQLException {
		List<Long> pids = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(BLOCKED_BY)) {
			statement.setInt(1, (int) blockerPid);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					pids.add(rs.getLong(1));
				}
			}
		}
		return pids;
	}

	/** Polls until at least {@code count} backends are blocked by {@code blockerPid}, or fails. */
	public List<Long> awaitBlockedBy(long blockerPid, int count, Duration timeout)
			throws SQLException, InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		List<Long> blocked = blockedBy(blockerPid);
		while (blocked.size() < count) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Expected " + count + " backend(s) blocked by " + blockerPid + " but saw "
						+ blocked + " within " + timeout + ". Overlap was not observed.");
			}
			Thread.sleep(5);
			blocked = blockedBy(blockerPid);
		}
		return blocked;
	}

	@Override
	public void close() throws SQLException {
		connection.close();
	}

}
