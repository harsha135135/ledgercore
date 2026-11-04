package io.github.harsha135135.ledgercore.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.harsha135135.ledgercore.ledger.JournalCursor;
import io.github.harsha135135.ledgercore.outbox.OutboxEvent.OutboxState;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

	private static final String EVENT_COLUMNS = "id, audit_event_id, type, schema_version, payload_json, state, "
			+ "attempts, total_attempts, next_attempt_at, published_at, last_error_code, created_at";

	private final JdbcTemplate jdbc;

	public OutboxRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Called inside the business transaction, so the event commits or rolls back with the change. */
	public void append(UUID eventId, UUID auditEventId, String type, int schemaVersion, String payloadJson) {
		jdbc.update("INSERT INTO outbox_events (id, audit_event_id, type, schema_version, payload_json, state) "
				+ "VALUES (?, ?, ?, ?, ?, 'PENDING')", eventId, auditEventId, type, schemaVersion, payloadJson);
	}

	/** PENDING events whose retry time has arrived and whose attempts are below the limit. */
	public List<OutboxEvent> findDue(int maxAttempts, int batchSize) {
		return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM outbox_events "
				+ "WHERE state = 'PENDING' AND next_attempt_at <= now() AND attempts < ? "
				+ "ORDER BY next_attempt_at, id LIMIT ?", OutboxRepository::mapEvent, maxAttempts, batchSize);
	}

	/**
	 * If the attempt limit is lowered, events already at or above it would never be selected again.
	 * Moving them to FAILED keeps them visible instead of silently stranded in PENDING.
	 */
	public int failExhaustedPending(int maxAttempts) {
		return jdbc.update("UPDATE outbox_events SET state = 'FAILED', "
				+ "last_error_code = COALESCE(last_error_code, 'ATTEMPT_LIMIT_REACHED') "
				+ "WHERE state = 'PENDING' AND attempts >= ?", maxAttempts);
	}

	/** Records a successful attempt. A no-op if an operator changed the event meanwhile. */
	public void markPublished(UUID eventId) {
		jdbc.update("UPDATE outbox_events SET state = 'PUBLISHED', published_at = now(), attempts = attempts + 1, "
				+ "total_attempts = total_attempts + 1, last_error_code = NULL WHERE id = ? AND state = 'PENDING'",
				eventId);
	}

	/** Records a failed attempt: back off below the limit, become FAILED at it. One statement, one short transaction. */
	public void recordFailure(UUID eventId, String errorCode, int maxAttempts, Duration nextDelay) {
		jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, total_attempts = total_attempts + 1, "
				+ "last_error_code = ?, "
				+ "state = CASE WHEN attempts + 1 >= ? THEN 'FAILED' ELSE 'PENDING' END, "
				+ "next_attempt_at = now() + (? * interval '1 millisecond') "
				+ "WHERE id = ? AND state = 'PENDING'", errorCode, maxAttempts, nextDelay.toMillis(), eventId);
	}

	public Optional<OutboxEvent> findById(UUID eventId) {
		return jdbc.query("SELECT " + EVENT_COLUMNS + " FROM outbox_events WHERE id = ?", OutboxRepository::mapEvent,
				eventId).stream().findFirst();
	}

	public OutboxEvent lockById(UUID eventId) {
		return jdbc.queryForObject("SELECT " + EVENT_COLUMNS + " FROM outbox_events WHERE id = ? FOR UPDATE",
				OutboxRepository::mapEvent, eventId);
	}

	/** Manual retry: reset the current attempt count, keep the event ID and total attempts. */
	public OutboxEvent requeue(UUID eventId) {
		return jdbc.queryForObject("UPDATE outbox_events SET state = 'PENDING', attempts = 0, last_error_code = NULL, "
				+ "next_attempt_at = now() WHERE id = ? AND state = 'FAILED' RETURNING " + EVENT_COLUMNS,
				OutboxRepository::mapEvent, eventId);
	}

	public List<OutboxEvent> findPage(OutboxState state, JournalCursor after, int limit) {
		String select = "SELECT " + EVENT_COLUMNS + " FROM outbox_events WHERE state = ? ";
		String order = "ORDER BY created_at, id LIMIT ?";
		if (after == null) {
			return jdbc.query(select + order, OutboxRepository::mapEvent, state.name(), limit);
		}
		return jdbc.query(select + "AND (created_at, id) > (?, ?) " + order, OutboxRepository::mapEvent, state.name(),
				after.createdAt(), after.journalId(), limit);
	}

	public long countByState(OutboxState state) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE state = ?", Long.class,
				state.name());
		return count == null ? 0 : count;
	}

	/** Age in seconds of the oldest event not yet published (PENDING or FAILED), or 0 if none. */
	public double oldestUndeliveredAgeSeconds() {
		Double age = jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) "
				+ "FROM outbox_events WHERE state <> 'PUBLISHED'", Double.class);
		return age == null ? 0 : age;
	}

	private static OutboxEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
		return new OutboxEvent(
				rs.getObject("id", UUID.class),
				rs.getObject("audit_event_id", UUID.class),
				rs.getString("type"),
				rs.getInt("schema_version"),
				rs.getString("payload_json"),
				OutboxState.valueOf(rs.getString("state")),
				rs.getInt("attempts"),
				rs.getInt("total_attempts"),
				rs.getObject("next_attempt_at", OffsetDateTime.class),
				rs.getObject("published_at", OffsetDateTime.class),
				rs.getString("last_error_code"),
				rs.getObject("created_at", OffsetDateTime.class));
	}

}
