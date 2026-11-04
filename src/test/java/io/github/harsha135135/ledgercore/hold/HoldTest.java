package io.github.harsha135135.ledgercore.hold;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.hold.HoldMessages.CreateHoldCommand;
import io.github.harsha135135.ledgercore.hold.HoldMessages.HoldResponse;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.github.harsha135135.ledgercore.support.ConcurrentCalls;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.LockObserver;
import io.github.harsha135135.ledgercore.support.TestCheckpoints.PauseGate;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static io.github.harsha135135.ledgercore.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldTest extends IntegrationTest {

	@Autowired
	private HoldService holdService;

	@Test
	void holdReservesAvailabilityWithoutMovingMoney() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);

		MutationResult created = createHold(source, destination, 3_000, TestFixtures.newKey());
		assertThat(created.httpStatus()).isEqualTo(201);
		assertThat(postedBalance(source)).isEqualTo(10_000);
		assertThat(reservedBalance(source)).isEqualTo(3_000);
		assertThat(journalCountFor(source)).as("only the funding journal").isEqualTo(1);

		// Reserved funds cannot be spent: 7,000 is available, so 8,000 is refused.
		MutationResult transfer = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
				new TransferCommand(source, destination, 8_000), TestFixtures.newKey(), TestFixtures.requestId());
		assertThat(transfer.httpStatus()).isEqualTo(422);
		assertThat(createHold(source, destination, 7_001, TestFixtures.newKey()).httpStatus()).isEqualTo(422);
		assertLedgerConsistent();
	}

	@Test
	void captureTransfersTheWholeReservationOnce() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID holdId = holdIdOf(createHold(source, destination, 3_000, TestFixtures.newKey()));

		MutationResult capture = holdService.captureHold(TestUsers.CUSTOMER_A_ID, holdId, TestFixtures.newKey(),
				TestFixtures.requestId());
		assertThat(capture.httpStatus()).isEqualTo(201);
		assertThat(TestFixtures.field(capture, "kind")).isEqualTo("CAPTURE");
		assertThat(postedBalance(source)).isEqualTo(7_000);
		assertThat(reservedBalance(source)).isZero();
		assertThat(postedBalance(destination)).isEqualTo(3_000);

		HoldResponse hold = holdService.getHold(TestUsers.CUSTOMER_A_ID, holdId, "r");
		assertThat(hold.state()).isEqualTo("CAPTURED");
		assertThat(hold.captureJournalId()).isEqualTo(TestFixtures.field(capture, "journalId"));
		assertThat(hold.closedAt()).isNotNull();
		assertLedgerConsistent();
	}

	@Test
	void releaseReturnsTheReservationWithoutPostings() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID holdId = holdIdOf(createHold(source, destination, 3_000, TestFixtures.newKey()));

		MutationResult release = holdService.releaseHold(TestUsers.CUSTOMER_A_ID, holdId, TestFixtures.newKey(),
				TestFixtures.requestId());
		assertThat(release.httpStatus()).isEqualTo(200);
		assertThat(TestFixtures.field(release, "state")).isEqualTo("RELEASED");
		assertThat(postedBalance(source)).isEqualTo(10_000);
		assertThat(reservedBalance(source)).isZero();
		assertThat(journalCountFor(destination)).isZero();

		HoldResponse hold = holdService.getHold(TestUsers.CUSTOMER_A_ID, holdId, "r");
		assertThat(hold.captureJournalId()).isNull();
		assertThat(hold.closedAt()).isNotNull();
		assertLedgerConsistent();
	}

	@Test
	void terminalHoldRejectsNewKeysButReplaysTheOriginalKey() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID holdId = holdIdOf(createHold(source, destination, 1_000, TestFixtures.newKey()));
		String captureKey = TestFixtures.newKey();
		MutationResult capture = holdService.captureHold(TestUsers.CUSTOMER_A_ID, holdId, captureKey, "r1");

		MutationResult replay = holdService.captureHold(TestUsers.CUSTOMER_A_ID, holdId, captureKey, "r2");
		assertThat(replay.replayed()).isTrue();
		assertThat(replay.bodyJson()).isEqualTo(capture.bodyJson());

		MutationResult secondCapture = holdService.captureHold(TestUsers.CUSTOMER_A_ID, holdId,
				TestFixtures.newKey(), "r3");
		assertThat(secondCapture.httpStatus()).isEqualTo(409);
		assertThat(secondCapture.bodyJson()).contains("HOLD_NOT_ACTIVE");
		MutationResult releaseAfterCapture = holdService.releaseHold(TestUsers.CUSTOMER_A_ID, holdId,
				TestFixtures.newKey(), "r4");
		assertThat(releaseAfterCapture.httpStatus()).isEqualTo(409);
		assertThat(postedBalance(destination)).isEqualTo(1_000);
	}

	@Test
	void recipientCannotSeeOrActOnTheSendersHoldButAdminCan() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID holdId = holdIdOf(createHold(source, destination, 2_000, TestFixtures.newKey()));

		assertThatThrownBy(() -> holdService.getHold(TestUsers.CUSTOMER_B_ID, holdId, "r"))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(404));
		assertThatThrownBy(() -> holdService.captureHold(TestUsers.CUSTOMER_B_ID, holdId, TestFixtures.newKey(), "r"))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(404));
		assertThat(api().get("/v1/holds/" + holdId, TestUsers.CUSTOMER_B).status()).isEqualTo(404);

		assertThat(api().get("/v1/holds/" + holdId, TestUsers.ADMIN).text("state")).isEqualTo("ACTIVE");
		MutationResult adminRelease = holdService.releaseHold(TestUsers.ADMIN_ID, holdId, TestFixtures.newKey(), "r");
		assertThat(adminRelease.httpStatus()).isEqualTo(200);
		assertThat(reservedBalance(source)).isZero();
	}

	@Test
	void holdLifecycleWorksOverHttp() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		var created = api().post("/v1/holds", TestUsers.CUSTOMER_A, json("sourceAccountId", source.toString(),
				"destinationAccountId", destination.toString(), "amountMinor", "2500", "currency", "INR"));
		assertThat(created.status()).isEqualTo(201);
		assertThat(created.text("state")).isEqualTo("ACTIVE");
		var captured = api().post("/v1/holds/" + created.text("holdId") + "/capture", TestUsers.CUSTOMER_A, "");
		assertThat(captured.status()).isEqualTo(201);
		assertThat(captured.text("kind")).isEqualTo("CAPTURE");
		var balance = api().get("/v1/accounts/" + source + "/balance", TestUsers.CUSTOMER_A);
		assertThat(balance.text("postedMinor")).isEqualTo("7500");
		assertThat(balance.text("reservedMinor")).isEqualTo("0");
	}

	@Test
	void releaseWaitsForAnInFlightCaptureThenFindsTheHoldClosed() throws Exception {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID holdId = holdIdOf(createHold(source, destination, 4_000, TestFixtures.newKey()));
		ExecutorService executor = Executors.newFixedThreadPool(2);
		PauseGate gate = checkpoints.pauseNextAt(Checkpoint.AFTER_ACCOUNT_LOCKS);
		try (gate; LockObserver observer = LockObserver.open()) {
			Future<MutationResult> capture = executor.submit(() -> holdService.captureHold(TestUsers.CUSTOMER_A_ID,
					holdId, TestFixtures.newKey(), TestFixtures.requestId()));
			long capturePid = gate.awaitArrival(Duration.ofSeconds(5));
			Future<MutationResult> release = executor.submit(() -> holdService.releaseHold(TestUsers.CUSTOMER_A_ID,
					holdId, TestFixtures.newKey(), TestFixtures.requestId()));
			assertThat(observer.awaitBlockedBy(capturePid, 1, Duration.ofMillis(1200))).hasSize(1);
			gate.release();
			assertThat(capture.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
			MutationResult releaseResult = release.get(10, TimeUnit.SECONDS);
			assertThat(releaseResult.httpStatus()).isEqualTo(409);
			assertThat(releaseResult.bodyJson()).contains("HOLD_NOT_ACTIVE");
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(reservedBalance(source)).isZero();
		assertThat(postedBalance(source)).isEqualTo(6_000);
		assertLedgerConsistent();
	}

	@Test
	void captureRacingReleaseEndsInExactlyOneTerminalStateEveryRound() throws Exception {
		int rounds = 200;
		long holdAmount = 10;
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, rounds * holdAmount);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		int captures = 0;
		int releases = 0;
		try {
			for (int round = 0; round < rounds; round++) {
				UUID holdId = holdIdOf(createHold(source, destination, holdAmount, TestFixtures.newKey()));
				CountDownLatch start = new CountDownLatch(1);
				Future<MutationResult> capture = executor.submit(() -> {
					start.await();
					return ConcurrentCalls.withTransientRetry(() -> holdService.captureHold(TestUsers.CUSTOMER_A_ID,
							holdId, "cap-" + holdId, TestFixtures.requestId()), 10).result();
				});
				Future<MutationResult> release = executor.submit(() -> {
					start.await();
					return ConcurrentCalls.withTransientRetry(() -> holdService.releaseHold(TestUsers.CUSTOMER_A_ID,
							holdId, "rel-" + holdId, TestFixtures.requestId()), 10).result();
				});
				start.countDown();
				MutationResult captureResult = capture.get(10, TimeUnit.SECONDS);
				MutationResult releaseResult = release.get(10, TimeUnit.SECONDS);

				boolean captureWon = captureResult.isSuccess();
				boolean releaseWon = releaseResult.isSuccess();
				assertThat(captureWon ^ releaseWon).as("exactly one winner in round " + round).isTrue();
				MutationResult loser = captureWon ? releaseResult : captureResult;
				assertThat(loser.httpStatus()).isEqualTo(409);
				String state = holdService.getHold(TestUsers.CUSTOMER_A_ID, holdId, "r").state();
				assertThat(state).isEqualTo(captureWon ? "CAPTURED" : "RELEASED");
				if (captureWon) {
					captures++;
				}
				else {
					releases++;
				}
			}
		}
		finally {
			executor.shutdownNow();
		}
		System.out.printf("Observation: capture won %d and release won %d of %d rounds.%n", captures, releases,
				rounds);
		assertThat(captures + releases).isEqualTo(rounds);
		assertThat(reservedBalance(source)).isZero();
		assertThat(postedBalance(source)).isEqualTo(rounds * holdAmount - captures * holdAmount);
		assertThat(postedBalance(destination)).isEqualTo(captures * holdAmount);
		assertLedgerConsistent();
	}

	private MutationResult createHold(UUID source, UUID destination, long amount, String key) {
		return holdService.createHold(TestUsers.CUSTOMER_A_ID, new CreateHoldCommand(source, destination, amount), key,
				TestFixtures.requestId());
	}

	private static UUID holdIdOf(MutationResult created) {
		if (!created.isSuccess()) {
			throw new AssertionError("Hold not created: " + created.bodyJson());
		}
		return UUID.fromString(TestFixtures.field(created, "holdId"));
	}

}
