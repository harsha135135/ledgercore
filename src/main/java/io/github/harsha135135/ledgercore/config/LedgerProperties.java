package io.github.harsha135135.ledgercore.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validated application settings, loaded once from configuration.
 * Limits are demo product choices, not measured capacity.
 */
@ConfigurationProperties("ledgercore")
public record LedgerProperties(Limits limits, Security security, Outbox outbox, DemoConsumer demoConsumer,
		Diagnostics diagnostics) {

	public record Limits(
			long maxAmountMinor,
			int maxRequestBytes,
			int defaultPageSize,
			int maxPageSize,
			int maxIdempotencyKeyLength) {
	}

	public record Security(List<UserAccount> users) {

		/** Returns the principal subject configured for a username; absent means the user is not provisioned. */
		public Optional<String> subjectForUsername(String username) {
			if (users == null) {
				return Optional.empty();
			}
			for (UserAccount user : users) {
				if (user.username().equals(username)) {
					return Optional.ofNullable(user.subject());
				}
			}
			return Optional.empty();
		}
	}

	/** One configured login. The password hash uses Spring's delegating format, for example {bcrypt}$2a$... */
	public record UserAccount(String username, String subject, String passwordHash) {
	}

	/**
	 * Delivery settings for the single outbox poller. The publisher password is the client-side
	 * secret matching the consumer's configured hash.
	 */
	public record Outbox(
			boolean pollerEnabled,
			Duration pollInterval,
			int batchSize,
			Duration deliveryTimeout,
			int maxAttempts,
			Duration backoffBase,
			Duration backoffMax,
			String consumerUrl,
			String publisherUsername,
			String publisherPassword) {
	}

	/** The demonstration consumer's service credential, separate from the ledger users. */
	public record DemoConsumer(String username, String passwordHash) {
	}

	/**
	 * Read-only diagnostics: the Prometheus scrape login (separate from the ledger users, so it never
	 * receives ledger access) and the source revision reported in the startup config record.
	 */
	public record Diagnostics(String username, String passwordHash, String revision) {
	}

}
