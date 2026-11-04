package io.github.harsha135135.ledgercore.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The application role can append history but never rewrite it. Checked against real grants. */
class DatabaseRoleTest extends IntegrationTest {

	@Test
	void applicationRoleCannotRewriteJournalHistory() throws SQLException {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 5_000);
		try (Connection app = PostgresTestDatabase.openAppConnection(); Statement statement = app.createStatement()) {
			assertPermissionDenied(statement, "UPDATE postings SET amount_minor = 1 WHERE account_id = '" + account + "'");
			assertPermissionDenied(statement, "DELETE FROM postings WHERE account_id = '" + account + "'");
			assertPermissionDenied(statement, "UPDATE journals SET amount_minor = 1");
			assertPermissionDenied(statement, "DELETE FROM journals");
			assertPermissionDenied(statement, "DELETE FROM audit_events");
			assertPermissionDenied(statement, "UPDATE audit_events SET action = 'X'");
		}
	}

	@Test
	void applicationRoleCannotChangeAccountIdentityOrOwnership() throws SQLException {
		UUID account = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);
		try (Connection app = PostgresTestDatabase.openAppConnection(); Statement statement = app.createStatement()) {
			assertPermissionDenied(statement,
					"UPDATE accounts SET owner_id = '" + TestUsers.CUSTOMER_B_ID + "' WHERE id = '" + account + "'");
			assertPermissionDenied(statement, "UPDATE accounts SET kind = 'SYSTEM_CONTROL' WHERE id = '" + account + "'");
			assertPermissionDenied(statement, "DELETE FROM accounts WHERE id = '" + account + "'");
			assertPermissionDenied(statement, "UPDATE principals SET role = 'ADMIN'");
		}
	}

	@Test
	void onlyTheMigrationRoleCanCreateTheControlAccount() throws SQLException {
		try (Connection app = PostgresTestDatabase.openAppConnection(); Statement statement = app.createStatement()) {
			assertThatThrownBy(() -> statement.execute(
					"INSERT INTO accounts (id, owner_id, kind, currency, state) VALUES ('" + UUID.randomUUID()
							+ "', NULL, 'SYSTEM_CONTROL', 'INR', 'ACTIVE')"))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("row-level security");
		}
	}

	@Test
	void customerBalanceCannotGoNegativeEvenWithDirectSql() throws SQLException {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 100);
		try (Connection app = PostgresTestDatabase.openAppConnection(); Statement statement = app.createStatement()) {
			assertThatThrownBy(() -> statement.execute(
					"UPDATE accounts SET credit_balance_minor = -1 WHERE id = '" + account + "'"))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("customer_account_rules");
		}
		assertThat(postedBalance(account)).isEqualTo(100);
	}

	private static void assertPermissionDenied(Statement statement, String sql) {
		assertThatThrownBy(() -> statement.execute(sql)).as(sql)
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("permission denied");
	}

}
