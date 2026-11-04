package io.github.harsha135135.ledgercore.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation must notice tampering the application could not have caused. The schema owner
 * plants a discrepancy, the check reports it, and the test restores the row.
 */
class ReconciliationTest extends IntegrationTest {

	@Test
	void detectsAProjectionThatNoLongerMatchesHistory() throws SQLException {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 5_000);
		try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
			statement.execute("UPDATE accounts SET credit_balance_minor = 5001 WHERE id = '" + account + "'");
			try {
				List<String> problems = reconciliationService.reconcile();
				assertThat(problems).anyMatch(p -> p.contains(account.toString()) && p.contains("5001"));
				assertThat(problems).anyMatch(p -> p.contains("instead of 0"));
			}
			finally {
				statement.execute("UPDATE accounts SET credit_balance_minor = 5000 WHERE id = '" + account + "'");
			}
		}
		assertLedgerConsistent();
	}

	@Test
	void detectsAJournalWithAMissingPosting() throws SQLException {
		UUID account = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);
		UUID journalId = UUID.fromString(fixtures.fund(account, 700));
		try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
			// Remove the credit line and its effect, so balances still agree but the journal is one-sided.
			owner.setAutoCommit(false);
			statement.execute("DELETE FROM postings WHERE journal_id = '" + journalId + "' AND side = 'CREDIT'");
			statement.execute("UPDATE accounts SET credit_balance_minor = 0 WHERE id = '" + account + "'");
			owner.commit();
			try {
				List<String> problems = reconciliationService.reconcile();
				assertThat(problems).anyMatch(p -> p.contains(journalId.toString()));
			}
			finally {
				statement.execute("INSERT INTO postings (journal_id, line_no, account_id, side, amount_minor) VALUES ('"
						+ journalId + "', 2, '" + account + "', 'CREDIT', 700)");
				statement.execute("UPDATE accounts SET credit_balance_minor = 700 WHERE id = '" + account + "'");
				owner.commit();
			}
		}
		assertLedgerConsistent();
	}

	@Test
	void adminCanRunReconciliationOverHttp() {
		var response = api().get("/v1/admin/reconciliation", TestUsers.ADMIN);
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body().get("consistent").asBoolean()).isTrue();
	}

}
