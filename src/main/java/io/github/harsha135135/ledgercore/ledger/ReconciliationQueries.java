package io.github.harsha135135.ledgercore.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only checks that compare the balance projection with journal history. Each returns at
 * most {@value #MAX_REPORTED} offending rows. Sums use PostgreSQL numeric, which is wider than
 * bigint, so a total cannot overflow while it is being checked.
 */
@Repository
public class ReconciliationQueries {

	static final int MAX_REPORTED = 100;

	private final JdbcTemplate jdbc;

	public ReconciliationQueries(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Journals without exactly one debit and one credit of the journal amount on two different accounts. */
	public List<UUID> unbalancedJournals() {
		return jdbc.queryForList("""
				SELECT j.id
				FROM journals j
				LEFT JOIN postings p ON p.journal_id = j.id
				GROUP BY j.id, j.amount_minor
				HAVING count(p.journal_id) <> 2
				    OR count(*) FILTER (WHERE p.side = 'DEBIT') <> 1
				    OR count(*) FILTER (WHERE p.side = 'CREDIT') <> 1
				    OR count(DISTINCT p.account_id) <> 2
				    OR bool_or(p.amount_minor <> j.amount_minor)
				ORDER BY j.id
				LIMIT ?
				""", UUID.class, MAX_REPORTED);
	}

	/** Accounts whose stored balance differs from credits minus debits rebuilt from postings. */
	public List<ProjectionMismatch> projectionMismatches() {
		return jdbc.query("""
				SELECT a.id, a.credit_balance_minor AS stored, rebuilt.total AS rebuilt
				FROM accounts a
				CROSS JOIN LATERAL (
				    SELECT COALESCE(SUM(CASE WHEN p.side = 'CREDIT' THEN p.amount_minor::numeric
				                             ELSE -p.amount_minor::numeric END), 0) AS total
				    FROM postings p
				    WHERE p.account_id = a.id
				) rebuilt
				WHERE a.credit_balance_minor::numeric <> rebuilt.total
				ORDER BY a.id
				LIMIT ?
				""", (rs, rowNum) -> new ProjectionMismatch(rs.getObject("id", UUID.class), rs.getBigDecimal("stored"),
				rs.getBigDecimal("rebuilt")), MAX_REPORTED);
	}

	/** Sum over every account, including SYSTEM_CONTROL. Every journal balances, so this must be zero. */
	public BigDecimal totalOfAllBalances() {
		return jdbc.queryForObject("SELECT COALESCE(SUM(credit_balance_minor::numeric), 0) FROM accounts",
				BigDecimal.class);
	}

	/** Customer accounts with negative available funds (the CHECK constraint should make this impossible). */
	public List<UUID> customersWithNegativeAvailability() {
		return jdbc.queryForList("""
				SELECT id FROM accounts
				WHERE kind = 'CUSTOMER' AND credit_balance_minor - reserved_minor < 0
				ORDER BY id LIMIT ?
				""", UUID.class, MAX_REPORTED);
	}

	public int systemControlAccountCount() {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM accounts WHERE kind = 'SYSTEM_CONTROL'",
				Integer.class);
		return count == null ? 0 : count;
	}

	public List<UUID> systemControlAccountsWithReservations() {
		return jdbc.queryForList(
				"SELECT id FROM accounts WHERE kind = 'SYSTEM_CONTROL' AND reserved_minor <> 0 ORDER BY id LIMIT ?",
				UUID.class, MAX_REPORTED);
	}

	/** Customer accounts whose reserved amount differs from the total of their ACTIVE holds. */
	public List<ProjectionMismatch> reservationMismatches() {
		return jdbc.query("""
				SELECT a.id, a.reserved_minor AS stored, COALESCE(active.total, 0) AS rebuilt
				FROM accounts a
				LEFT JOIN (
				    SELECT source_id, SUM(amount_minor::numeric) AS total
				    FROM holds WHERE state = 'ACTIVE' GROUP BY source_id
				) active ON active.source_id = a.id
				WHERE a.reserved_minor::numeric <> COALESCE(active.total, 0)
				ORDER BY a.id
				LIMIT ?
				""", (rs, rowNum) -> new ProjectionMismatch(rs.getObject("id", UUID.class), rs.getBigDecimal("stored"),
				rs.getBigDecimal("rebuilt")), MAX_REPORTED);
	}

	/**
	 * CAPTURED holds whose capture journal is missing or does not match: it must be a CAPTURE of the
	 * hold amount that debits the hold source and credits the hold destination.
	 */
	public List<UUID> capturedHoldsWithoutMatchingJournal() {
		return jdbc.queryForList("""
				SELECT h.id
				FROM holds h
				LEFT JOIN journals j ON j.id = h.capture_journal_id
				LEFT JOIN postings debit ON debit.journal_id = j.id AND debit.side = 'DEBIT'
				LEFT JOIN postings credit ON credit.journal_id = j.id AND credit.side = 'CREDIT'
				WHERE h.state = 'CAPTURED'
				  AND (j.id IS NULL
				       OR j.kind <> 'CAPTURE'
				       OR j.amount_minor <> h.amount_minor
				       OR debit.account_id IS DISTINCT FROM h.source_id
				       OR credit.account_id IS DISTINCT FROM h.destination_id)
				ORDER BY h.id
				LIMIT ?
				""", UUID.class, MAX_REPORTED);
	}

	/** CAPTURE journals that no hold points to. */
	public List<UUID> captureJournalsWithoutHold() {
		return jdbc.queryForList("""
				SELECT j.id FROM journals j
				WHERE j.kind = 'CAPTURE'
				  AND NOT EXISTS (SELECT 1 FROM holds h WHERE h.capture_journal_id = j.id)
				ORDER BY j.id
				LIMIT ?
				""", UUID.class, MAX_REPORTED);
	}

	public record ProjectionMismatch(UUID accountId, BigDecimal storedMinor, BigDecimal rebuiltMinor) {
	}

}
