package io.github.harsha135135.ledgercore.hold;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HoldRepository {

	private static final String HOLD_COLUMNS =
			"id, source_id, destination_id, amount_minor, state, capture_journal_id, created_at, closed_at";

	private final JdbcTemplate jdbc;

	public HoldRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** An ordinary read. Safe for immutable facts (participants, amount); never for the state. */
	public Optional<HoldRow> findById(UUID holdId) {
		return jdbc.query("SELECT " + HOLD_COLUMNS + " FROM holds WHERE id = ?", HoldRepository::mapHold, holdId)
			.stream()
			.findFirst();
	}

	/** Locks the hold row. Called only after its accounts are locked, so every path uses one order. */
	public HoldRow lockById(UUID holdId) {
		List<HoldRow> rows = jdbc.query("SELECT " + HOLD_COLUMNS + " FROM holds WHERE id = ? FOR UPDATE",
				HoldRepository::mapHold, holdId);
		if (rows.isEmpty()) {
			throw new IllegalStateException("Hold disappeared before it could be locked: " + holdId);
		}
		return rows.get(0);
	}

	public HoldRow insertActive(UUID holdId, UUID sourceId, UUID destinationId, long amountMinor) {
		return jdbc.queryForObject("INSERT INTO holds (id, source_id, destination_id, amount_minor, state) "
				+ "VALUES (?, ?, ?, ?, 'ACTIVE') RETURNING " + HOLD_COLUMNS, HoldRepository::mapHold, holdId, sourceId,
				destinationId, amountMinor);
	}

	public HoldRow markCaptured(UUID holdId, UUID captureJournalId) {
		return jdbc.queryForObject("UPDATE holds SET state = 'CAPTURED', capture_journal_id = ?, closed_at = now() "
				+ "WHERE id = ? AND state = 'ACTIVE' RETURNING " + HOLD_COLUMNS, HoldRepository::mapHold,
				captureJournalId, holdId);
	}

	public HoldRow markReleased(UUID holdId) {
		return jdbc.queryForObject("UPDATE holds SET state = 'RELEASED', closed_at = now() "
				+ "WHERE id = ? AND state = 'ACTIVE' RETURNING " + HOLD_COLUMNS, HoldRepository::mapHold, holdId);
	}

	private static HoldRow mapHold(ResultSet rs, int rowNum) throws SQLException {
		return new HoldRow(
				rs.getObject("id", UUID.class),
				rs.getObject("source_id", UUID.class),
				rs.getObject("destination_id", UUID.class),
				rs.getLong("amount_minor"),
				HoldState.valueOf(rs.getString("state")),
				rs.getObject("capture_journal_id", UUID.class),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("closed_at", OffsetDateTime.class));
	}

}
