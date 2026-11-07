package io.github.harsha135135.ledgercore.config;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Writes one diagnostic record when the application is ready: the settings that shape delivery and
 * database waits, the source revision and a per-process instance ID. Only allowlisted, non-secret
 * values are written: never passwords, password hashes or full URLs with credentials.
 *
 * <p>Configuration history comes from these records (one per start), so a restart adds a new
 * record instead of overwriting the previous one.
 */
@Component
public class StartupConfigRecord {

	private static final Logger records = LoggerFactory.getLogger("ledgercore.diagnostics");
	private static final Pattern BCRYPT_COST = Pattern.compile("^\\{bcrypt\\}\\$2[aby]?\\$(\\d{2})\\$");

	private final LedgerProperties properties;
	private final DataSource dataSource;
	private final String instanceId = UUID.randomUUID().toString();

	public StartupConfigRecord(LedgerProperties properties, DataSource dataSource) {
		this.properties = properties;
		this.dataSource = dataSource;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		write();
	}

	/** Writes the record. Public so a test can check exactly what it contains. */
	public void write() {
		LedgerProperties.Outbox outbox = properties.outbox();
		LoggingEventBuilder record = records.atInfo()
			.addKeyValue("record", "ledgercore.config")
			.addKeyValue("instanceId", instanceId)
			.addKeyValue("revision", revision())
			.addKeyValue("effectiveAt", Instant.now().toString())
			.addKeyValue("outboxPollerEnabled", outbox.pollerEnabled())
			.addKeyValue("outboxWorkers", 1)
			.addKeyValue("outboxPollIntervalMs", outbox.pollInterval().toMillis())
			.addKeyValue("outboxBatchSize", outbox.batchSize())
			.addKeyValue("outboxDeliveryTimeoutMs", outbox.deliveryTimeout().toMillis())
			.addKeyValue("outboxMaxAttempts", outbox.maxAttempts())
			.addKeyValue("outboxBackoffBaseMs", outbox.backoffBase().toMillis())
			.addKeyValue("outboxBackoffMaxMs", outbox.backoffMax().toMillis())
			.addKeyValue("consumerTarget", consumerTarget(outbox.consumerUrl()))
			.addKeyValue("consumerAuthentication", "HTTP Basic")
			.addKeyValue("inProcessConsumerBcryptCost", inProcessConsumerBcryptCost());
		if (dataSource instanceof HikariDataSource hikari) {
			record = record.addKeyValue("poolMaximumSize", hikari.getMaximumPoolSize())
				.addKeyValue("poolMinimumIdle", hikari.getMinimumIdle())
				.addKeyValue("poolConnectionTimeoutMs", hikari.getConnectionTimeout())
				// The session options string holds only timeouts, for example "-c lock_timeout=2s ...".
				.addKeyValue("databaseSessionOptions", hikari.getDataSourceProperties().getProperty("options", ""));
		}
		record.log("LedgerCore configuration at startup");
	}

	private String revision() {
		LedgerProperties.Diagnostics diagnostics = properties.diagnostics();
		if (diagnostics == null || diagnostics.revision() == null || diagnostics.revision().isBlank()) {
			return "unknown";
		}
		return diagnostics.revision();
	}

	/** "in-process" for the built-in demonstration consumer, otherwise the URL without user info or query. */
	public static String consumerTarget(String consumerUrl) {
		if (consumerUrl == null || consumerUrl.isBlank()) {
			return "in-process";
		}
		try {
			URI uri = URI.create(consumerUrl);
			String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
			return uri.getScheme() + "://" + uri.getHost() + port + (uri.getPath() == null ? "" : uri.getPath());
		}
		catch (IllegalArgumentException ex) {
			return "external (unparseable URL)";
		}
	}

	/** The BCrypt cost of the in-process consumer's configured hash, read from its prefix; never the hash. */
	private String inProcessConsumerBcryptCost() {
		LedgerProperties.DemoConsumer consumer = properties.demoConsumer();
		if (consumer == null || consumer.passwordHash() == null) {
			return "not configured";
		}
		Matcher matcher = BCRYPT_COST.matcher(consumer.passwordHash());
		return matcher.find() ? matcher.group(1) : "unknown";
	}

}
