package io.github.harsha135135.ledgercore.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadinessTest extends IntegrationTest {

	@Test
	void readinessRequiresExactlyOneActiveControlAccount() throws SQLException {
		assertThat(api().getAnonymous("/actuator/health/readiness").status()).isEqualTo(200);

		try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
			statement.execute("UPDATE accounts SET state = 'CLOSED' WHERE kind = 'SYSTEM_CONTROL'");
			try {
				assertThat(api().getAnonymous("/actuator/health/readiness").status()).isEqualTo(503);
			}
			finally {
				statement.execute("UPDATE accounts SET state = 'ACTIVE' WHERE kind = 'SYSTEM_CONTROL'");
			}
		}
		assertThat(api().getAnonymous("/actuator/health/readiness").status()).isEqualTo(200);
	}

}
