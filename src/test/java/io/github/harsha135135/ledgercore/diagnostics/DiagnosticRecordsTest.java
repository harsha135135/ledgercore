package io.github.harsha135135.ledgercore.diagnostics;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import io.github.harsha135135.ledgercore.config.StartupConfigRecord;
import io.github.harsha135135.ledgercore.consumer.DemoConsumer;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.github.harsha135135.ledgercore.outbox.OutboxDiagnostics;
import io.github.harsha135135.ledgercore.outbox.OutboxPoller;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery-attempt, poll-batch and startup-config records: the fields IncidentLens reads, and no
 * event payload, amount, account or secret in any of them. Records are captured from the
 * diagnostics logger, which tests switch on (it is off by default).
 */
class DiagnosticRecordsTest extends IntegrationTest {

	private static final List<String> DELIVERY_KEYS = List.of("record", "outboxEventId", "attempt", "totalAttempt",
			"startedAt", "endedAt", "durationMs", "result", "httpStatus", "transitionRecorded");

	@Autowired
	private OutboxPoller poller;

	@Autowired
	private DemoConsumer consumer;

	@Autowired
	private StartupConfigRecord startupConfig;

	private final Logger recordLogger = (Logger) LoggerFactory.getLogger(OutboxDiagnostics.RECORD_LOGGER);
	private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
	private Level previousLevel;

	@BeforeEach
	void captureRecords() {
		consumer.setAccepting(true);
		poller.pollOnce();
		previousLevel = recordLogger.getLevel();
		recordLogger.setLevel(Level.INFO);
		captured.start();
		recordLogger.addAppender(captured);
	}

	@AfterEach
	void stopCapturing() {
		recordLogger.detachAppender(captured);
		recordLogger.setLevel(previousLevel);
		consumer.setAccepting(true);
	}

	@Test
	void successfulDeliveryRecordHasTimesAndResultButNoPayload() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		poller.pollOnce();
		captured.list.clear();
		UUID eventId = transferEvent(source, destination, 987_651);

		poller.pollOnce();

		Map<String, Object> record = deliveryRecordFor(eventId);
		assertThat(record.keySet()).containsExactlyElementsOf(DELIVERY_KEYS);
		assertThat(record.get("attempt")).isEqualTo(1);
		assertThat(record.get("totalAttempt")).isEqualTo(1);
		assertThat(record.get("result")).isEqualTo("SUCCESS");
		assertThat(record.get("httpStatus")).isEqualTo(200);
		assertThat(record.get("transitionRecorded")).isEqualTo(true);
		Instant started = Instant.parse((String) record.get("startedAt"));
		Instant ended = Instant.parse((String) record.get("endedAt"));
		assertThat((String) record.get("startedAt")).endsWith("Z");
		assertThat(ended).isAfterOrEqualTo(started);
		assertThat((Double) record.get("durationMs")).isGreaterThanOrEqualTo(0.0);
		assertNoPayload(record, source, destination, "987651");

		Map<String, Object> batch = onlyRecord("outbox.poll_batch");
		assertThat((Integer) batch.get("selected")).isGreaterThanOrEqualTo(1);
		assertThat((Integer) batch.get("published")).isGreaterThanOrEqualTo(1);
		assertThat(batch.get("configuredDelayMs")).isEqualTo(1000L);
		assertThat(batch.get("batchSize")).isEqualTo(200);
		assertNoPayload(batch, source, destination, "987651");
	}

	@Test
	void failedDeliveryRecordKeepsTheStatusAndRecordsNoTransition() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		poller.pollOnce();
		captured.list.clear();
		UUID eventId = transferEvent(source, destination, 876_543);
		consumer.setAccepting(false);

		poller.pollOnce();

		Map<String, Object> record = deliveryRecordFor(eventId);
		assertThat(record.get("result")).isEqualTo("HTTP_503");
		assertThat(record.get("httpStatus")).isEqualTo(503);
		assertThat(record.get("transitionRecorded")).isEqualTo(false);
		assertNoPayload(record, source, destination, "876543");
	}

	/**
	 * Regression: during a delivery the poller puts eventId in the MDC, and the ECS file format writes
	 * MDC entries and key-value pairs as top-level fields. A key-value pair with the same name made the
	 * encoder fail, and Logback dropped the record silently. This runs the real ECS encoder.
	 */
	@Test
	void deliveryRecordsEncodeAsEcsJsonAlongsideTheMdcEventId() throws Exception {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		StructuredLogEncoder encoder = new StructuredLogEncoder();
		encoder.setFormat("ecs");
		encoder.setContext(context);
		encoder.start();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		OutputStreamAppender<ILoggingEvent> jsonFile = new OutputStreamAppender<>();
		jsonFile.setContext(context);
		jsonFile.setEncoder(encoder);
		jsonFile.setOutputStream(output);
		jsonFile.start();
		recordLogger.addAppender(jsonFile);
		try {
			UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000_000);
			UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
			poller.pollOnce();
			output.reset();
			UUID eventId = transferEvent(source, destination, 765_432);

			poller.pollOnce();

			JsonMapper mapper = JsonMapper.builder().build();
			List<JsonNode> lines = output.toString(StandardCharsets.UTF_8).lines().map(mapper::readTree).toList();
			JsonNode delivery = lines.stream()
				.filter(line -> "outbox.delivery_attempt".equals(line.path("record").asString(null)))
				.filter(line -> eventId.toString().equals(line.path("outboxEventId").asString(null)))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no ECS delivery record for " + eventId + " in: " + lines));
			assertThat(delivery.path("eventId").asString(null)).as("MDC event ID").isEqualTo(eventId.toString());
			assertThat(delivery.path("@timestamp").asString("")).endsWith("Z");
			assertThat(delivery.path("result").asString(null)).isEqualTo("SUCCESS");
			assertThat(lines.stream().anyMatch(line -> "outbox.poll_batch".equals(line.path("record").asString(null))))
				.isTrue();
		}
		finally {
			recordLogger.detachAppender(jsonFile);
			jsonFile.stop();
		}
	}

	@Test
	void idlePollsAreTimedButNotLogged() {
		for (int pass = 0; pass < 50 && poller.pollOnce().selected() > 0; pass++) {
			// deliver anything other tests left behind
		}
		captured.list.clear();
		poller.pollOnce();
		assertThat(captured.list).isEmpty();
	}

	@Test
	void startupConfigRecordHoldsOnlyAllowlistedValues() {
		captured.list.clear();
		startupConfig.write();

		Map<String, Object> record = onlyRecord("ledgercore.config");
		assertThat(record.keySet()).containsExactlyInAnyOrder("record", "instanceId", "revision", "effectiveAt",
				"outboxPollerEnabled", "outboxWorkers", "outboxPollIntervalMs", "outboxBatchSize",
				"outboxDeliveryTimeoutMs", "outboxMaxAttempts", "outboxBackoffBaseMs", "outboxBackoffMaxMs",
				"consumerTarget", "consumerAuthentication", "inProcessConsumerBcryptCost", "poolMaximumSize",
				"poolMinimumIdle", "poolConnectionTimeoutMs", "databaseSessionOptions");
		assertThat(record.get("revision")).isEqualTo("unknown");
		assertThat(record.get("outboxPollerEnabled")).isEqualTo(false);
		assertThat(record.get("outboxBatchSize")).isEqualTo(200);
		assertThat(record.get("consumerTarget")).isEqualTo("in-process");
		assertThat(record.get("inProcessConsumerBcryptCost")).isEqualTo("04");
		assertThat(record.get("poolMaximumSize")).isEqualTo(10);
		assertThat(record.get("databaseSessionOptions"))
			.isEqualTo("-c lock_timeout=2s -c statement_timeout=5s -c transaction_timeout=10s");
		assertThat(Instant.parse((String) record.get("effectiveAt"))).isBeforeOrEqualTo(Instant.now());

		String values = record.values().toString();
		assertThat(values).doesNotContain("{bcrypt}")
			.doesNotContain(TestUsers.password(TestUsers.PUBLISHER))
			.doesNotContain(PostgresTestDatabase.APP_PASSWORD)
			.doesNotContain(PostgresTestDatabase.ownerPassword());
	}

	@Test
	void externalConsumerTargetDropsCredentialsAndQuery() {
		assertThat(StartupConfigRecord.consumerTarget("http://user:secret@127.0.0.1:18090/events?token=abc"))
			.isEqualTo("http://127.0.0.1:18090/events");
	}

	private UUID transferEvent(UUID source, UUID destination, long amount) {
		MutationResult result = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
				new TransferCommand(source, destination, amount), TestFixtures.newKey(), TestFixtures.requestId());
		assertThat(result.httpStatus()).isEqualTo(201);
		String journalId = TestFixtures.field(result, "journalId");
		return jdbc.queryForObject("SELECT id FROM outbox_events WHERE type = 'ledger.transfer_posted' "
				+ "AND payload_json::jsonb ->> 'resourceId' = ?", UUID.class, journalId);
	}

	private Map<String, Object> deliveryRecordFor(UUID eventId) {
		List<Map<String, Object>> matches = records("outbox.delivery_attempt").stream()
			.filter(record -> eventId.toString().equals(record.get("outboxEventId")))
			.toList();
		assertThat(matches).as("delivery records for " + eventId).hasSize(1);
		return matches.get(0);
	}

	private Map<String, Object> onlyRecord(String type) {
		List<Map<String, Object>> matches = records(type);
		assertThat(matches).as(type + " records").hasSize(1);
		return matches.get(0);
	}

	private List<Map<String, Object>> records(String type) {
		return captured.list.stream().map(DiagnosticRecordsTest::keyValues)
			.filter(record -> type.equals(record.get("record")))
			.toList();
	}

	private static Map<String, Object> keyValues(ILoggingEvent event) {
		Map<String, Object> values = new LinkedHashMap<>();
		if (event.getKeyValuePairs() != null) {
			for (KeyValuePair pair : event.getKeyValuePairs()) {
				values.put(pair.key, pair.value);
			}
		}
		return values;
	}

	private static void assertNoPayload(Map<String, Object> record, UUID source, UUID destination, String amount) {
		String values = record.values().toString();
		assertThat(values).doesNotContain(source.toString()).doesNotContain(destination.toString())
			.doesNotContain(amount).doesNotContain("payload");
	}

}
