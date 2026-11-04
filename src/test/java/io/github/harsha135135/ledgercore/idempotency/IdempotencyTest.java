package io.github.harsha135135.ledgercore.idempotency;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.api.MutationResponses;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.github.harsha135135.ledgercore.support.ApiClient;
import io.github.harsha135135.ledgercore.support.ApiClient.ApiResponse;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.TestCheckpoints.InjectedFailure;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;

import static io.github.harsha135135.ledgercore.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Retries, lost responses and crashes: the money moves at most once per key. */
class IdempotencyTest extends IntegrationTest {

	@Test
	void responseLostAfterCommitIsRecoveredByRetryingTheSameKey() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String body = transfer(source, destination, "3000");
		String key = "lost-response-" + UUID.randomUUID();
		ApiClient api = api();

		// The transfer commits, then the response is lost: the client sees a failure.
		checkpoints.failNextAt(Checkpoint.AFTER_COMMIT);
		ApiResponse lost = api.postWithKey("/v1/transfers", TestUsers.CUSTOMER_A, body, key);
		assertThat(lost.status()).isEqualTo(500);
		assertThat(postedBalance(source)).as("money already moved").isEqualTo(7_000);

		// The client retries with the same key and receives the committed result.
		ApiResponse retry = api.postWithKey("/v1/transfers", TestUsers.CUSTOMER_A, body, key);
		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.header(MutationResponses.REPLAY_HEADER)).isEqualTo("true");
		String storedJournalId = jdbc.queryForObject(
				"SELECT p.journal_id::text FROM postings p WHERE p.account_id = ? AND p.side = 'CREDIT'", String.class,
				destination);
		assertThat(retry.text("journalId")).isEqualTo(storedJournalId);

		assertThat(journalCountFor(destination)).isEqualTo(1);
		assertThat(postedBalance(source)).isEqualTo(7_000);
		assertLedgerConsistent();
	}

	@Test
	void crashBeforeCommitLeavesNoJournalBalanceChangeKeyOrAuditEvent() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String key = TestFixtures.newKey();
		int auditBefore = transferAuditCount(source);

		checkpoints.failNextAt(Checkpoint.BEFORE_COMMIT);
		assertThatThrownBy(() -> transfer(source, destination, 3_000, key)).isInstanceOf(InjectedFailure.class);

		assertThat(postedBalance(source)).isEqualTo(10_000);
		assertThat(postedBalance(destination)).isZero();
		assertThat(journalCountFor(destination)).isZero();
		assertThat(transferAuditCount(source)).isEqualTo(auditBefore);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE key = ?", Integer.class, key))
			.as("the rolled-back claim must not survive").isZero();

		// The key was not consumed, so the same request can now run normally.
		MutationResult retry = transfer(source, destination, 3_000, key);
		assertThat(retry.httpStatus()).isEqualTo(201);
		assertThat(retry.replayed()).isFalse();
		assertLedgerConsistent();
	}

	@Test
	void sameKeyWithADifferentBodyIsAConflictAndMovesNothing() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String key = TestFixtures.newKey();
		assertThat(transfer(source, destination, 1_000, key).httpStatus()).isEqualTo(201);

		assertThatThrownBy(() -> transfer(source, destination, 2_000, key))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.status().value()).isEqualTo(409);
				assertThat(ex.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
			});
		assertThat(postedBalance(source)).isEqualTo(9_000);
		assertThat(journalCountFor(destination)).isEqualTo(1);
	}

	@Test
	void businessRejectionIsStoredAndStaysRejectedOnRetry() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String key = TestFixtures.newKey();

		MutationResult first = transfer(source, destination, 5_000, key);
		assertThat(first.httpStatus()).isEqualTo(422);
		fixtures.fund(source, 10_000);

		// Same key: still the original rejection, even though funds now suffice.
		MutationResult retry = transfer(source, destination, 5_000, key);
		assertThat(retry.httpStatus()).isEqualTo(422);
		assertThat(retry.replayed()).isTrue();
		assertThat(retry.bodyJson()).isEqualTo(first.bodyJson());

		// A deliberate new attempt uses a new key.
		assertThat(transfer(source, destination, 5_000, TestFixtures.newKey()).httpStatus()).isEqualTo(201);
	}

	@Test
	void demotedAdministratorCannotReplayAStoredFundingResponse() throws SQLException {
		UUID account = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);
		String body = json("destinationAccountId", account.toString(), "amountMinor", "500", "currency", "INR",
				"reason", "replay-after-demotion test");
		String key = "funding-" + UUID.randomUUID();
		ApiClient api = api();

		ApiResponse original = api.postWithKey("/v1/demo-funding", TestUsers.ADMIN, body, key);
		assertThat(original.status()).isEqualTo(201);

		TestFixtures.setRole(TestUsers.ADMIN_ID, "CUSTOMER");
		try {
			ApiResponse replayWhileDemoted = api.postWithKey("/v1/demo-funding", TestUsers.ADMIN, body, key);
			assertThat(replayWhileDemoted.status()).isEqualTo(403);
			assertThat(replayWhileDemoted.text("code")).isEqualTo("FORBIDDEN_OPERATION");
			assertThat(replayWhileDemoted.rawBody()).doesNotContain(original.text("journalId"));
		}
		finally {
			TestFixtures.setRole(TestUsers.ADMIN_ID, "ADMIN");
		}

		ApiResponse replayAfterRestore = api.postWithKey("/v1/demo-funding", TestUsers.ADMIN, body, key);
		assertThat(replayAfterRestore.status()).isEqualTo(201);
		assertThat(replayAfterRestore.text("journalId")).isEqualTo(original.text("journalId"));
		assertThat(postedBalance(account)).isEqualTo(500);
	}

	@Test
	void everyMutationRequiresAValidKey() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ApiClient api = api();
		ApiResponse missing = api.post("/v1/transfers", TestUsers.CUSTOMER_A, transfer(source, destination, "10"),
				Map.of());
		assertThat(missing.status()).isEqualTo(400);
		assertThat(missing.text("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
		ApiResponse tooLong = api.postWithKey("/v1/transfers", TestUsers.CUSTOMER_A, transfer(source, destination, "10"),
				"k".repeat(129));
		assertThat(tooLong.status()).isEqualTo(400);
		assertThat(tooLong.text("code")).isEqualTo("INVALID_IDEMPOTENCY_KEY");
		assertThat(postedBalance(source)).isEqualTo(1_000);
	}

	@Test
	void keysAreScopedToTheCaller() {
		UUID sourceA = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		UUID sourceB = fixtures.newFundedAccount(TestUsers.CUSTOMER_B_ID, 1_000);
		UUID destinationA = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);
		String sharedKey = "shared-" + UUID.randomUUID();
		assertThat(transfer(sourceA, destinationA, 100, sharedKey).httpStatus()).isEqualTo(201);
		MutationResult otherCaller = ledgerService.transfer(TestUsers.CUSTOMER_B_ID,
				new TransferCommand(sourceB, destinationA, 100), sharedKey, TestFixtures.requestId());
		assertThat(otherCaller.httpStatus()).isEqualTo(201);
		assertThat(otherCaller.replayed()).isFalse();
	}

	private MutationResult transfer(UUID source, UUID destination, long amount, String key) {
		return ledgerService.transfer(TestUsers.CUSTOMER_A_ID, new TransferCommand(source, destination, amount), key,
				TestFixtures.requestId());
	}

	private int transferAuditCount(UUID source) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE action = 'TRANSFER_POSTED' AND details_json->>'sourceAccountId' = ?",
				Integer.class, source.toString());
		return count == null ? 0 : count;
	}

	private static String transfer(UUID source, UUID destination, String amountMinor) {
		return json("sourceAccountId", source.toString(), "destinationAccountId", destination.toString(),
				"amountMinor", amountMinor, "currency", "INR");
	}

}
