package io.github.harsha135135.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Named SQL for journals and postings. The application role can insert and read, never update or delete. */
@Repository
public class JournalRepository {

	private final JdbcTemplate jdbc;

	public JournalRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public OffsetDateTime insertJournal(UUID journalId, JournalKind kind, long amountMinor, UUID actorId,
			UUID reversesJournalId) {
		return jdbc.queryForObject(
				"INSERT INTO journals (id, kind, amount_minor, actor_id, reverses_id) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING created_at",
				OffsetDateTime.class, journalId, kind.name(), amountMinor, actorId, reversesJournalId);
	}

	public void insertPosting(UUID journalId, int lineNo, UUID accountId, PostingSide side, long amountMinor) {
		jdbc.update("INSERT INTO postings (journal_id, line_no, account_id, side, amount_minor) VALUES (?, ?, ?, ?, ?)",
				journalId, lineNo, accountId, side.name(), amountMinor);
	}

	/**
	 * One page of the entries touching an account, oldest first. Returns up to {@code limit} rows;
	 * callers ask for one extra row to learn whether another page exists.
	 */
	public List<AccountJournalEntry> findPageForAccount(UUID accountId, JournalCursor after, int limit) {
		String select = "SELECT j.id, j.kind, p.side, p.amount_minor, j.created_at "
				+ "FROM postings p JOIN journals j ON j.id = p.journal_id WHERE p.account_id = ? ";
		String order = "ORDER BY j.created_at, j.id LIMIT ?";
		if (after == null) {
			return jdbc.query(select + order, JournalRepository::mapEntry, accountId, limit);
		}
		return jdbc.query(select + "AND (j.created_at, j.id) > (?, ?) " + order, JournalRepository::mapEntry,
				accountId, after.createdAt(), after.journalId(), limit);
	}

	public Optional<JournalRow> findById(UUID journalId) {
		return jdbc.query("SELECT id, kind, amount_minor, reverses_id FROM journals WHERE id = ?",
				(rs, rowNum) -> new JournalRow(rs.getObject("id", UUID.class), JournalKind.valueOf(rs.getString("kind")),
						rs.getLong("amount_minor"), rs.getObject("reverses_id", UUID.class)),
				journalId)
			.stream()
			.findFirst();
	}

	/** Postings are immutable once written, so this ordinary read is safe before taking locks. */
	public List<PostingRow> findPostings(UUID journalId) {
		return jdbc.query(
				"SELECT line_no, account_id, side, amount_minor FROM postings WHERE journal_id = ? ORDER BY line_no",
				(rs, rowNum) -> new PostingRow(rs.getInt("line_no"), rs.getObject("account_id", UUID.class),
						PostingSide.valueOf(rs.getString("side")), rs.getLong("amount_minor")),
				journalId);
	}

	/** A fresh statement, run after the account locks are held, so a just-committed reversal is visible. */
	public Optional<UUID> findReversalOf(UUID journalId) {
		return jdbc.queryForList("SELECT id FROM journals WHERE reverses_id = ?", UUID.class, journalId)
			.stream()
			.findFirst();
	}

	private static AccountJournalEntry mapEntry(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new AccountJournalEntry(
				rs.getObject("id", UUID.class),
				JournalKind.valueOf(rs.getString("kind")),
				PostingSide.valueOf(rs.getString("side")),
				rs.getLong("amount_minor"),
				rs.getObject("created_at", OffsetDateTime.class));
	}

	/** One account's side of a journal. */
	public record AccountJournalEntry(UUID journalId, JournalKind kind, PostingSide side, long amountMinor,
			OffsetDateTime createdAt) {
	}

	public record JournalRow(UUID id, JournalKind kind, long amountMinor, UUID reversesJournalId) {
	}

	public record PostingRow(int lineNo, UUID accountId, PostingSide side, long amountMinor) {
	}

}
