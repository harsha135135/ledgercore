package io.github.harsha135135.ledgercore.support;

import io.github.harsha135135.ledgercore.ledger.AccountService;
import io.github.harsha135135.ledgercore.ledger.LedgerService;
import io.github.harsha135135.ledgercore.ledger.ReconciliationService;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for tests against the real application and a real PostgreSQL container.
 * All subclasses share one Spring context and one database; each test creates its own accounts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IntegrationTest.TestSupport.class)
public abstract class IntegrationTest {

	@LocalServerPort
	protected int port;

	@Autowired
	protected TestFixtures fixtures;

	@Autowired
	protected TestCheckpoints checkpoints;

	@Autowired
	protected AccountService accountService;

	@Autowired
	protected LedgerService ledgerService;

	@Autowired
	protected ReconciliationService reconciliationService;

	@Autowired
	protected JdbcTemplate jdbc;

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", PostgresTestDatabase::jdbcUrl);
		registry.add("spring.datasource.username", () -> PostgresTestDatabase.APP_USER);
		registry.add("spring.datasource.password", () -> PostgresTestDatabase.APP_PASSWORD);
		registry.add("spring.flyway.url", PostgresTestDatabase::jdbcUrl);
		registry.add("spring.flyway.user", PostgresTestDatabase::ownerUser);
		registry.add("spring.flyway.password", PostgresTestDatabase::ownerPassword);
		// Tests drive the poller by hand, with millisecond backoff so retries do not slow the suite.
		registry.add("ledgercore.outbox.poller-enabled", () -> "false");
		registry.add("ledgercore.outbox.backoff-base", () -> "1ms");
		registry.add("ledgercore.outbox.backoff-max", () -> "4ms");
		registry.add("ledgercore.outbox.batch-size", () -> "200");
		TestUsers.register(registry);
	}

	@AfterEach
	void resetCheckpoints() {
		checkpoints.reset();
	}

	protected ApiClient api() {
		return new ApiClient(port);
	}

	/** Every test that changes money should leave the ledger consistent. */
	protected void assertLedgerConsistent() {
		assertThat(reconciliationService.reconcile()).as("reconciliation problems").isEmpty();
	}

	protected long postedBalance(java.util.UUID accountId) {
		Long value = jdbc.queryForObject("SELECT credit_balance_minor FROM accounts WHERE id = ?", Long.class,
				accountId);
		return value == null ? 0 : value;
	}

	protected long reservedBalance(java.util.UUID accountId) {
		Long value = jdbc.queryForObject("SELECT reserved_minor FROM accounts WHERE id = ?", Long.class, accountId);
		return value == null ? 0 : value;
	}

	protected int journalCountFor(java.util.UUID accountId) {
		Integer value = jdbc.queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Integer.class,
				accountId);
		return value == null ? 0 : value;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestSupport {

		@Bean
		@Primary
		TestCheckpoints testCheckpoints(JdbcTemplate jdbc) {
			return new TestCheckpoints(jdbc);
		}

		@Bean
		TestFixtures testFixtures(AccountService accountService, LedgerService ledgerService) {
			return new TestFixtures(accountService, ledgerService);
		}

	}

}
