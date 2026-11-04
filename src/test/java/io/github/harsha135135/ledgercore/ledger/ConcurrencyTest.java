package io.github.harsha135135.ledgercore.ledger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariDataSource;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.BalanceResponse;
import io.github.harsha135135.ledgercore.support.ConcurrentCalls;
import io.github.harsha135135.ledgercore.support.ConcurrentCalls.Outcome;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.LockObserver;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;
import io.github.harsha135135.ledgercore.support.TestCheckpoints.PauseGate;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Race tests through the real services and real PostgreSQL.
 *
 * <p>Gate tests give decisive evidence: transaction A is paused at a checkpoint, B is started, and
 * the observer confirms through {@code pg_blocking_pids} that B is blocked by A before A is
 * released. Burst tests then check outcomes at volume.
 */
class ConcurrencyTest extends IntegrationTest {

	private static final Duration GATE_WAIT = Duration.ofSeconds(5);
	private static final Duration OBSERVE_WAIT = Duration.ofMillis(1200);
	private static final Duration BURST_TIMEOUT = Duration.ofSeconds(60);
	private static final int RETRY_BUDGET = 10;

	@Autowired
	private HikariDataSource dataSource;

	@Test
	void secondTransferWaitsForTheFirstsLockThenSeesTheReducedBalance() throws Exception {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 5_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		PauseGate gate = checkpoints.pauseNextAt(Checkpoint.AFTER_ACCOUNT_LOCKS);
		try (gate; LockObserver observer = LockObserver.open()) {
			Future<MutationResult> first = executor.submit(() -> transfer(source, destination, 3_000));
			long firstPid = gate.awaitArrival(GATE_WAIT);

			Future<MutationResult> second = executor.submit(() -> transfer(source, destination, 3_000));
			List<Long> blocked = observer.awaitBlockedBy(firstPid, 1, OBSERVE_WAIT);
			assertThat(blocked).as("the second transfer is blocked by the first one's account lock").hasSize(1);
			gate.release();

			assertThat(first.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
			// The second transfer read the balance only after acquiring the lock: 2,000 is not enough.
			MutationResult secondResult = second.get(10, TimeUnit.SECONDS);
			assertThat(secondResult.httpStatus()).isEqualTo(422);
			assertThat(secondResult.bodyJson()).contains("INSUFFICIENT_AVAILABLE_FUNDS");
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(postedBalance(source)).isEqualTo(2_000);
		assertThat(postedBalance(destination)).isEqualTo(3_000);
		assertLedgerConsistent();
	}

	@Test
	void sameKeyRequestWaitsOnTheUncommittedClaimThenReplaysTheResult() throws Exception {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String key = TestFixtures.newKey();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		PauseGate gate = checkpoints.pauseNextAt(Checkpoint.AFTER_IDEMPOTENCY_CLAIM);
		try (gate; LockObserver observer = LockObserver.open()) {
			Future<MutationResult> first = executor.submit(() -> transfer(source, destination, 1_000, key));
			long firstPid = gate.awaitArrival(GATE_WAIT);

			Future<MutationResult> second = executor.submit(() -> transfer(source, destination, 1_000, key));
			assertThat(observer.awaitBlockedBy(firstPid, 1, OBSERVE_WAIT))
				.as("the duplicate is blocked by the uncommitted key claim").hasSize(1);
			gate.release();

			MutationResult original = first.get(10, TimeUnit.SECONDS);
			MutationResult duplicate = second.get(10, TimeUnit.SECONDS);
			assertThat(original.httpStatus()).isEqualTo(201);
			assertThat(original.replayed()).isFalse();
			assertThat(duplicate.replayed()).isTrue();
			assertThat(duplicate.bodyJson()).isEqualTo(original.bodyJson());
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(journalCountFor(destination)).isEqualTo(1);
		assertThat(postedBalance(source)).isEqualTo(9_000);
	}

	@Test
	void oppositeDirectionTransfersAcrossTheHex8BoundaryDoNotDeadlock() throws Exception {
		UUID low = TestFixtures.newAccountWithId(TestFixtures.uuidStartingWith('0'), TestUsers.CUSTOMER_A_ID);
		UUID high = TestFixtures.newAccountWithId(TestFixtures.uuidStartingWith('f'), TestUsers.CUSTOMER_A_ID);
		fixtures.fund(low, 100_000);
		fixtures.fund(high, 100_000);
		// For this pair the signed and canonical orders really disagree.
		assertThat(low.compareTo(high)).isPositive();
		assertThat(low.toString().compareTo(high.toString())).isNegative();

		// Decisive check: while A (low -> high) holds both locks, B (high -> low) waits on its
		// first lock instead of taking the other one, so no lock cycle can form.
		ExecutorService executor = Executors.newFixedThreadPool(2);
		PauseGate gate = checkpoints.pauseNextAt(Checkpoint.AFTER_ACCOUNT_LOCKS);
		try (gate; LockObserver observer = LockObserver.open()) {
			Future<MutationResult> first = executor.submit(() -> transfer(low, high, 100));
			long firstPid = gate.awaitArrival(GATE_WAIT);
			Future<MutationResult> second = executor.submit(() -> transfer(high, low, 100));
			assertThat(observer.awaitBlockedBy(firstPid, 1, OBSERVE_WAIT)).hasSize(1);
			gate.release();
			assertThat(first.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
			assertThat(second.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
		}
		finally {
			executor.shutdownNow();
		}

		// Burst: 40 transfers each way, started together.
		List<Outcome> outcomes = ConcurrentCalls.runTogether(80, i -> ConcurrentCalls.withTransientRetry(
				() -> i % 2 == 0 ? transfer(low, high, 10) : transfer(high, low, 10), RETRY_BUDGET), BURST_TIMEOUT);
		List<String> transientStates = new ArrayList<>();
		for (Outcome outcome : outcomes) {
			assertThat(outcome.result().httpStatus()).isEqualTo(201);
			transientStates.addAll(outcome.transientFailures());
		}
		assertThat(transientStates).as("no deadlock victims (SQLSTATE 40P01)").doesNotContain("40P01");
		assertThat(postedBalance(low)).isEqualTo(100_000);
		assertThat(postedBalance(high)).isEqualTo(100_000);
		assertLedgerConsistent();
	}

	@Test
	void hundredSameKeySubmissionsProduceExactlyOneJournal() throws Exception {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String key = TestFixtures.newKey();

		List<Outcome> outcomes = ConcurrentCalls.runTogether(100, i -> ConcurrentCalls
			.withTransientRetry(() -> transfer(source, destination, 1_000, key), RETRY_BUDGET), BURST_TIMEOUT);

		Set<String> distinctBodies = new HashSet<>();
		int replays = 0;
		for (Outcome outcome : outcomes) {
			assertThat(outcome.result().httpStatus()).isEqualTo(201);
			distinctBodies.add(outcome.result().bodyJson());
			if (outcome.result().replayed()) {
				replays++;
			}
		}
		assertThat(distinctBodies).as("every caller sees the same committed response").hasSize(1);
		assertThat(replays).isEqualTo(99);
		assertThat(journalCountFor(destination)).isEqualTo(1);
		assertThat(postedBalance(source)).isEqualTo(9_000);
		assertThat(postedBalance(destination)).isEqualTo(1_000);
		assertLedgerConsistent();
	}

	@Test
	void fiftyCompetingTransfersFromTenThousandLetExactlyThreeSucceed() throws Exception {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String runId = UUID.randomUUID().toString();

		List<Outcome> outcomes = ConcurrentCalls.runTogether(50, i -> ConcurrentCalls.withTransientRetry(
				() -> transfer(source, destination, 3_000, "compete-" + runId + "-" + i), RETRY_BUDGET), BURST_TIMEOUT);

		int succeeded = 0;
		int insufficient = 0;
		for (Outcome outcome : outcomes) {
			if (outcome.result().httpStatus() == 201) {
				succeeded++;
			}
			else if (outcome.result().httpStatus() == 422
					&& outcome.result().bodyJson().contains("INSUFFICIENT_AVAILABLE_FUNDS")) {
				insufficient++;
			}
		}
		assertThat(succeeded).isEqualTo(3);
		assertThat(insufficient).isEqualTo(47);
		assertThat(postedBalance(source)).isEqualTo(1_000);
		assertThat(postedBalance(destination)).isEqualTo(9_000);
		assertLedgerConsistent();
	}

	@Test
	void forbiddenTransferIsRejectedWithoutReachingTheVictimsLock() throws Exception {
		UUID victim = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 5_000);
		UUID thiefAccount = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		String key = TestFixtures.newKey();

		try (Connection holder = PostgresTestDatabase.openOwnerConnection();
				LockObserver observer = LockObserver.open();
				Statement statement = holder.createStatement()) {
			holder.setAutoCommit(false);
			statement.execute("SELECT id FROM accounts WHERE id = '" + victim + "' FOR UPDATE");
			long holderPid;
			try (ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
				rs.next();
				holderPid = rs.getLong(1);
			}

			// If the request reached the lock it would wait out the lock timeout and fail as transient.
			assertThatThrownBy(() -> ledgerService.transfer(TestUsers.CUSTOMER_B_ID,
					new TransferCommand(victim, thiefAccount, 100), key, TestFixtures.requestId()))
				.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(404));
			assertThat(observer.blockedBy(holderPid)).isEmpty();
			holder.rollback();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE key = ?", Integer.class, key))
			.as("no key claimed for a forbidden request").isZero();
		assertThat(postedBalance(victim)).isEqualTo(5_000);
	}

	@Test
	void unrelatedReadsWaitForPooledConnectionsDuringASameKeyBurstButFinish() throws Exception {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID unrelated = fixtures.newFundedAccount(TestUsers.CUSTOMER_B_ID, 500);
		String key = TestFixtures.newKey();
		int poolSize = dataSource.getMaximumPoolSize();
		int duplicates = 2 * poolSize;

		ExecutorService executor = Executors.newFixedThreadPool(duplicates + 2);
		PauseGate gate = checkpoints.pauseNextAt(Checkpoint.AFTER_IDEMPOTENCY_CLAIM);
		try (gate; LockObserver observer = LockObserver.open()) {
			Future<MutationResult> first = executor.submit(() -> transfer(source, destination, 1_000, key));
			long firstPid = gate.awaitArrival(GATE_WAIT);
			List<Future<MutationResult>> waiting = new ArrayList<>();
			for (int i = 0; i < duplicates; i++) {
				waiting.add(executor.submit(() -> transfer(source, destination, 1_000, key)));
			}
			// Every other pooled connection is now held by a duplicate blocked on the key claim.
			assertThat(observer.awaitBlockedBy(firstPid, poolSize - 1, OBSERVE_WAIT)).hasSize(poolSize - 1);

			long readStarted = System.nanoTime();
			Future<BalanceResponse> read = executor
				.submit(() -> accountService.getBalance(TestUsers.CUSTOMER_B_ID, unrelated, TestFixtures.requestId()));
			Thread.sleep(150);
			assertThat(read.isDone()).as("the unrelated read is queued for a pooled connection").isFalse();
			gate.release();

			BalanceResponse balance = read.get(5, TimeUnit.SECONDS);
			long waitedMillis = Duration.ofNanos(System.nanoTime() - readStarted).toMillis();
			assertThat(balance.postedMinor()).isEqualTo("500");
			assertThat(waitedMillis).as("bounded wait").isLessThan(5_000);
			System.out.printf("Observation: an unrelated balance read waited %d ms while %d same-key requests "
					+ "held or queued for a pool of %d connections.%n", waitedMillis, duplicates, poolSize);

			assertThat(first.get(10, TimeUnit.SECONDS).httpStatus()).isEqualTo(201);
			for (Future<MutationResult> duplicate : waiting) {
				assertThat(duplicate.get(10, TimeUnit.SECONDS).replayed()).isTrue();
			}
		}
		finally {
			executor.shutdownNow();
		}
		assertThat(journalCountFor(destination)).isEqualTo(1);
	}

	private MutationResult transfer(UUID source, UUID destination, long amount) {
		return transfer(source, destination, amount, TestFixtures.newKey());
	}

	private MutationResult transfer(UUID source, UUID destination, long amount, String key) {
		return ledgerService.transfer(TestUsers.CUSTOMER_A_ID, new TransferCommand(source, destination, amount), key,
				TestFixtures.requestId());
	}

}
