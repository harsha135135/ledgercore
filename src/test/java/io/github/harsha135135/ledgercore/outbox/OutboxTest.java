package io.github.harsha135135.ledgercore.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import io.github.harsha135135.ledgercore.consumer.DemoConsumer;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.hold.HoldMessages.CreateHoldCommand;
import io.github.harsha135135.ledgercore.hold.HoldService;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.github.harsha135135.ledgercore.outbox.OutboxEvent.OutboxState;
import io.github.harsha135135.ledgercore.outbox.OutboxPoller.PollSummary;
import io.github.harsha135135.ledgercore.support.ApiClient;
import io.github.harsha135135.ledgercore.support.ApiClient.ApiResponse;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;
import io.github.harsha135135.ledgercore.support.TestCheckpoints.InjectedFailure;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import static io.github.harsha135135.ledgercore.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transactional outbox, single poller, deduplicating consumer and operator recovery. */
class OutboxTest extends IntegrationTest {

	private static final String TRANSFER_EVENT = "ledger.transfer_posted";

	@Autowired
	private OutboxPoller poller;

	@Autowired
	private OutboxRepository outbox;

	@Autowired
	private EventPublisher publisher;

	@Autowired
	private DemoConsumer consumer;

	@Autowired
	private HoldService holdService;

	/** Delivers the backlog left by other test classes, so each test observes only its own event. */
	@BeforeEach
	void drainOutbox() throws InterruptedException {
		consumer.setAccepting(true);
		for (int pass = 0; pass < 200; pass++) {
			PollSummary summary = poller.pollOnce();
			if (summary.selected() == 0 && outbox.countByState(OutboxState.PENDING) == 0) {
				return;
			}
			Thread.sleep(5);
		}
		throw new AssertionError("Outbox backlog did not drain");
	}

	@AfterEach
	void restoreConsumer() {
		consumer.setAccepting(true);
	}

	@Test
	void everyMutationCommitsOneEventLinkedToItsAuditRecord() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID journalId = transfer(source, destination, 1_000);
		MutationResult hold = holdService.createHold(TestUsers.CUSTOMER_A_ID,
				new CreateHoldCommand(source, destination, 500), TestFixtures.newKey(), TestFixtures.requestId());
		UUID holdId = UUID.fromString(TestFixtures.field(hold, "holdId"));

		assertEventFor("ledger.account_created", destination, "ACCOUNT_CREATED");
		assertEventFor(TRANSFER_EVENT, journalId, "TRANSFER_POSTED");
		assertEventFor("ledger.hold_created", holdId, "HOLD_CREATED");
	}

	@Test
	void anEventIsNeverCreatedForAChangeThatRolledBack() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		int eventsBefore = totalEvents();

		checkpoints.failNextAt(Checkpoint.BEFORE_COMMIT);
		assertThatThrownBy(() -> transfer(source, destination, 1_000)).isInstanceOf(InjectedFailure.class);

		assertThat(totalEvents()).as("the rolled-back transfer left no event").isEqualTo(eventsBefore);
	}

	@Test
	void pollerDeliversAnEventAndTheConsumerAppliesItOnce() {
		UUID eventId = newTransferEvent();
		long appliedBefore = consumer.appliedCount(TRANSFER_EVENT);

		PollSummary summary = poller.pollOnce();

		assertThat(summary.published()).isEqualTo(1);
		OutboxEvent event = outbox.findById(eventId).orElseThrow();
		assertThat(event.state()).isEqualTo(OutboxState.PUBLISHED);
		assertThat(event.attempts()).isEqualTo(1);
		assertThat(event.totalAttempts()).isEqualTo(1);
		assertThat(event.publishedAt()).isNotNull();
		assertThat(consumer.hasReceived(eventId)).isTrue();
		assertThat(consumer.appliedCount(TRANSFER_EVENT)).isEqualTo(appliedBefore + 1);
	}

	@Test
	void consumerCrashAfterCommitBeforeAcknowledgingCausesAHarmlessRedelivery() throws InterruptedException {
		UUID eventId = newTransferEvent();
		long appliedBefore = consumer.appliedCount(TRANSFER_EVENT);

		// The consumer commits its inbox row and effect, then fails before acknowledging.
		checkpoints.failNextAt(Checkpoint.AFTER_COMMIT);
		poller.pollOnce();
		OutboxEvent afterLostAck = outbox.findById(eventId).orElseThrow();
		assertThat(afterLostAck.state()).isEqualTo(OutboxState.PENDING);
		assertThat(afterLostAck.lastErrorCode()).isEqualTo("HTTP_500");
		assertThat(consumer.hasReceived(eventId)).isTrue();
		assertThat(consumer.appliedCount(TRANSFER_EVENT)).isEqualTo(appliedBefore + 1);

		Thread.sleep(10);
		poller.pollOnce();
		OutboxEvent redelivered = outbox.findById(eventId).orElseThrow();
		assertThat(redelivered.state()).isEqualTo(OutboxState.PUBLISHED);
		assertThat(redelivered.totalAttempts()).isEqualTo(2);
		assertThat(consumer.appliedCount(TRANSFER_EVENT)).as("the duplicate was not applied again")
			.isEqualTo(appliedBefore + 1);
	}

	@Test
	void pollerCrashAfterSendingBeforeRecordingCausesAHarmlessRedelivery() {
		UUID eventId = newTransferEvent();
		long appliedBefore = consumer.appliedCount(TRANSFER_EVENT);

		// As if the poller process died after the consumer acknowledged but before PUBLISHED was recorded.
		assertThat(publisher.send(outbox.findById(eventId).orElseThrow()).acknowledged()).isTrue();
		assertThat(outbox.findById(eventId).orElseThrow().state()).isEqualTo(OutboxState.PENDING);

		poller.pollOnce();
		assertThat(outbox.findById(eventId).orElseThrow().state()).isEqualTo(OutboxState.PUBLISHED);
		assertThat(consumer.appliedCount(TRANSFER_EVENT)).isEqualTo(appliedBefore + 1);
	}

	@Test
	void consumerOutageExhaustsRetriesThenAnAdministratorRecoversTheSameEvent() throws InterruptedException {
		consumer.setAccepting(false);
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID journalId = transfer(source, destination, 2_500);
		UUID eventId = eventIdFor(TRANSFER_EVENT, journalId);
		long appliedBefore = consumer.appliedCount(TRANSFER_EVENT);

		for (int pass = 0; pass < 100 && outbox.findById(eventId).orElseThrow().state() == OutboxState.PENDING; pass++) {
			poller.pollOnce();
			Thread.sleep(6);
		}
		OutboxEvent failed = outbox.findById(eventId).orElseThrow();
		assertThat(failed.state()).isEqualTo(OutboxState.FAILED);
		assertThat(failed.attempts()).isEqualTo(8);
		assertThat(failed.totalAttempts()).isEqualTo(8);
		assertThat(failed.lastErrorCode()).isEqualTo("HTTP_503");
		assertThat(postedBalance(destination)).as("the ledger change stays committed").isEqualTo(2_500);

		// FAILED events never re-enter automatic polling.
		poller.pollOnce();
		assertThat(outbox.findById(eventId).orElseThrow().totalAttempts()).isEqualTo(8);

		ApiClient api = api();
		ApiResponse failedPage = api.get("/v1/admin/outbox?state=FAILED&limit=100", TestUsers.ADMIN);
		assertThat(failedPage.status()).isEqualTo(200);
		assertThat(failedPage.body().get("items").valueStream().map(item -> item.get("eventId").asString()))
			.contains(eventId.toString());

		String retryKey = "outbox-retry-" + UUID.randomUUID();
		String reason = json("reason", "consumer restored after outage");
		ApiResponse retry = api.postWithKey("/v1/admin/outbox/" + eventId + "/retry", TestUsers.ADMIN, reason, retryKey);
		assertThat(retry.status()).isEqualTo(200);
		assertThat(retry.text("state")).isEqualTo("PENDING");
		assertThat(retry.body().get("attempts").asInt()).isZero();
		assertThat(retry.body().get("totalAttempts").asInt()).isEqualTo(8);
		ApiResponse retryReplay = api.postWithKey("/v1/admin/outbox/" + eventId + "/retry", TestUsers.ADMIN, reason,
				retryKey);
		assertThat(retryReplay.rawBody()).isEqualTo(retry.rawBody());
		assertThat(retryReplay.header("Idempotent-Replay")).isEqualTo("true");

		consumer.setAccepting(true);
		poller.pollOnce();
		OutboxEvent published = outbox.findById(eventId).orElseThrow();
		assertThat(published.state()).isEqualTo(OutboxState.PUBLISHED);
		assertThat(published.id()).isEqualTo(eventId);
		assertThat(published.totalAttempts()).isEqualTo(9);
		assertThat(consumer.appliedCount(TRANSFER_EVENT)).isEqualTo(appliedBefore + 1);

		ApiResponse retryPublished = api.post("/v1/admin/outbox/" + eventId + "/retry", TestUsers.ADMIN, reason);
		assertThat(retryPublished.status()).isEqualTo(409);
		assertThat(retryPublished.text("code")).isEqualTo("EVENT_NOT_FAILED");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'OUTBOX_RETRY_REQUESTED' AND resource_id = ?",
				Integer.class, eventId)).isEqualTo(1);
	}

	@Test
	void eventsStrandedByALoweredAttemptLimitBecomeVisibleAsFailed() throws SQLException {
		UUID eventId = newTransferEvent();
		try (Connection owner = PostgresTestDatabase.openOwnerConnection(); Statement statement = owner.createStatement()) {
			// Simulates an event that already used 8 attempts under an older, higher limit.
			statement.execute("UPDATE outbox_events SET attempts = 8, total_attempts = 8 WHERE id = '" + eventId + "'");
		}
		poller.pollOnce();
		OutboxEvent event = outbox.findById(eventId).orElseThrow();
		assertThat(event.state()).isEqualTo(OutboxState.FAILED);
		assertThat(event.lastErrorCode()).isEqualTo("ATTEMPT_LIMIT_REACHED");
	}

	@Test
	void consumerRejectsADifferentPayloadUnderAKnownEventId() {
		UUID eventId = newTransferEvent();
		poller.pollOnce();
		String forged = "{\"eventId\":\"" + eventId + "\",\"type\":\"" + TRANSFER_EVENT
				+ "\",\"schemaVersion\":1,\"payload\":{\"amountMinor\":\"999999\"}}";
		ApiResponse response = api().post("/demo-consumer/events", TestUsers.PUBLISHER, forged);
		assertThat(response.status()).isEqualTo(409);
		assertThat(response.text("code")).isEqualTo("PAYLOAD_MISMATCH");
	}

	@Test
	void serviceCredentialAndLedgerLoginsCannotCrossOver() {
		ApiClient api = api();
		String event = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"x\",\"schemaVersion\":1,\"payload\":{}}";

		assertThat(api.get("/v1/accounts/" + UUID.randomUUID(), TestUsers.PUBLISHER).status()).isEqualTo(403);
		assertThat(api.post("/demo-consumer/events", TestUsers.CUSTOMER_A, event).status()).isEqualTo(403);
		assertThat(api.post("/demo-consumer/events", TestUsers.ADMIN, event).status()).isEqualTo(403);
		assertThat(api.post("/demo-consumer/events", TestUsers.PUBLISHER, event, Map.of()).status()).isEqualTo(200);

		assertThat(api.get("/v1/admin/outbox", TestUsers.CUSTOMER_A).status()).isEqualTo(403);
		assertThat(api.get("/v1/admin/outbox", TestUsers.ADMIN).status()).isEqualTo(200);
		assertThat(api.post("/v1/admin/outbox/" + UUID.randomUUID() + "/retry", TestUsers.CUSTOMER_A,
				json("reason", "not allowed")).status()).isEqualTo(403);
		assertThat(api.post("/v1/admin/outbox/" + UUID.randomUUID() + "/retry", TestUsers.ADMIN,
				json("reason", "no such event")).status()).isEqualTo(404);
		assertThat(api.get("/v1/admin/outbox?state=LOST", TestUsers.ADMIN).status()).isEqualTo(400);
	}

	@Test
	void outboxGaugesAreExposedToAuthenticatedOperators() {
		ApiResponse failed = api().get("/actuator/metrics/ledgercore.outbox.failed.events", TestUsers.ADMIN);
		assertThat(failed.status()).isEqualTo(200);
		ApiResponse age = api().get("/actuator/metrics/ledgercore.outbox.oldest.undelivered.age", TestUsers.ADMIN);
		assertThat(age.status()).isEqualTo(200);
		JsonNode measurement = age.body().get("measurements").get(0);
		assertThat(measurement.get("value").asDouble()).isGreaterThanOrEqualTo(0.0);
	}

	private UUID newTransferEvent() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		// The fixtures' own events are delivered first, so only the transfer event is pending.
		poller.pollOnce();
		return eventIdFor(TRANSFER_EVENT, transfer(source, destination, 1_000));
	}

	private UUID transfer(UUID source, UUID destination, long amount) {
		MutationResult result = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
				new TransferCommand(source, destination, amount), TestFixtures.newKey(), TestFixtures.requestId());
		assertThat(result.httpStatus()).isEqualTo(201);
		return UUID.fromString(TestFixtures.field(result, "journalId"));
	}

	private UUID eventIdFor(String type, UUID resourceId) {
		return jdbc.queryForObject(
				"SELECT id FROM outbox_events WHERE type = ? AND payload_json::jsonb ->> 'resourceId' = ?", UUID.class,
				type, resourceId.toString());
	}

	private void assertEventFor(String type, UUID resourceId, String auditAction) {
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT o.state, o.schema_version, a.action FROM outbox_events o "
						+ "JOIN audit_events a ON a.id = o.audit_event_id "
						+ "WHERE o.type = ? AND o.payload_json::jsonb ->> 'resourceId' = ?",
				type, resourceId.toString());
		assertThat(row).containsEntry("state", "PENDING").containsEntry("schema_version", 1)
			.containsEntry("action", auditAction);
	}

	private int totalEvents() {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
		return count == null ? 0 : count;
	}

}
