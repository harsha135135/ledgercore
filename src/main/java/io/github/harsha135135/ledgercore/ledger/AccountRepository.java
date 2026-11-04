package io.github.harsha135135.ledgercore.ledger;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Named SQL for accounts. Decides nothing about whether a payment is allowed. */
@Repository
public class AccountRepository {

	private static final String ACCOUNT_COLUMNS =
			"id, owner_id, kind, currency, state, credit_balance_minor, reserved_minor, created_at";

	private final JdbcTemplate jdbc;

	public AccountRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** An ordinary read with no lock. Safe only for immutable facts such as owner and kind. */
	public Optional<AccountRow> findById(UUID accountId) {
		List<AccountRow> rows = jdbc.query("SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id = ?",
				AccountRepository::mapAccount, accountId);
		return rows.stream().findFirst();
	}

	/**
	 * Locks one account row until the surrounding transaction ends. Call only through
	 * {@link AccountLocks}, which fixes the order in which accounts are locked.
	 */
	AccountRow lockById(UUID accountId) {
		List<AccountRow> rows = jdbc.query(
				"SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id = ? FOR UPDATE",
				AccountRepository::mapAccount, accountId);
		if (rows.isEmpty()) {
			// Accounts are never deleted, and callers check existence before locking.
			throw new IllegalStateException("Account disappeared before it could be locked: " + accountId);
		}
		return rows.get(0);
	}

	public AccountRow insertCustomerAccount(UUID accountId, UUID ownerId) {
		return jdbc.queryForObject(
				"INSERT INTO accounts (id, owner_id, kind, currency, state) "
						+ "VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE') RETURNING " + ACCOUNT_COLUMNS,
				AccountRepository::mapAccount, accountId, ownerId);
	}

	/** Writes new projection values computed by the caller from locked rows. */
	public void updateBalances(UUID accountId, long creditBalanceMinor, long reservedMinor) {
		jdbc.update("UPDATE accounts SET credit_balance_minor = ?, reserved_minor = ? WHERE id = ?",
				creditBalanceMinor, reservedMinor, accountId);
	}

	/** A balance from one statement, with the database time of that read. */
	public Optional<BalanceRow> readBalance(UUID accountId) {
		List<BalanceRow> rows = jdbc.query(
				"SELECT id, owner_id, kind, credit_balance_minor, reserved_minor, now() AS observed_at "
						+ "FROM accounts WHERE id = ?",
				(rs, rowNum) -> new BalanceRow(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
						AccountKind.valueOf(rs.getString("kind")), rs.getLong("credit_balance_minor"),
						rs.getLong("reserved_minor"), rs.getObject("observed_at", OffsetDateTime.class)),
				accountId);
		return rows.stream().findFirst();
	}

	public UUID findSystemControlId() {
		return jdbc.queryForObject("SELECT id FROM accounts WHERE kind = 'SYSTEM_CONTROL'", UUID.class);
	}

	public int countActiveSystemControlAccounts() {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM accounts WHERE kind = 'SYSTEM_CONTROL' AND state = 'ACTIVE'", Integer.class);
		return count == null ? 0 : count;
	}

	private static AccountRow mapAccount(ResultSet rs, int rowNum) throws SQLException {
		return new AccountRow(
				rs.getObject("id", UUID.class),
				rs.getObject("owner_id", UUID.class),
				AccountKind.valueOf(rs.getString("kind")),
				rs.getString("currency"),
				AccountState.valueOf(rs.getString("state")),
				rs.getLong("credit_balance_minor"),
				rs.getLong("reserved_minor"),
				rs.getObject("created_at", OffsetDateTime.class));
	}

	public record BalanceRow(UUID accountId, UUID ownerId, AccountKind kind, long creditBalanceMinor,
			long reservedMinor, OffsetDateTime observedAt) {
	}

}
