package io.github.harsha135135.ledgercore.support;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.database.TransactionCheckpoints;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only checkpoints. A pause gate holds the first transaction to reach a checkpoint, records
 * its PostgreSQL backend PID, and waits for the test to release it. A failure trigger throws once
 * at a checkpoint to model a crash before commit or a response lost after commit.
 *
 * <p>Every wait is bounded, so a broken test fails instead of hanging. Never used in normal runs
 * or measurements.
 */
public class TestCheckpoints implements TransactionCheckpoints {

	/**
	 * Safety net if a test forgets to release. Blocked peers start waiting after the pause begins,
	 * so this stays below the 2-second lock timeout they would otherwise hit.
	 */
	private static final Duration MAX_PAUSE = Duration.ofMillis(1800);

	private final JdbcTemplate jdbc;
	private final AtomicReference<PauseGate> pauseGate = new AtomicReference<>();
	private final AtomicReference<Checkpoint> failAt = new AtomicReference<>();

	public TestCheckpoints(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void reached(Checkpoint checkpoint) {
		if (failAt.compareAndSet(checkpoint, null)) {
			throw new InjectedFailure(checkpoint);
		}
		PauseGate gate = pauseGate.get();
		if (gate != null && gate.checkpoint == checkpoint && gate.claimed.compareAndSet(false, true)) {
			// Inside the transaction, so this runs on the transaction's own connection.
			Long backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Long.class);
			gate.backendPid = backendPid == null ? -1 : backendPid;
			gate.arrived.countDown();
			gate.awaitRelease();
		}
	}

	/** Pauses the next transaction to reach {@code checkpoint}. Later arrivals pass straight through. */
	public PauseGate pauseNextAt(Checkpoint checkpoint) {
		PauseGate gate = new PauseGate(checkpoint);
		pauseGate.set(gate);
		return gate;
	}

	/** Throws {@link InjectedFailure} once, at the next arrival at {@code checkpoint}. */
	public void failNextAt(Checkpoint checkpoint) {
		failAt.set(checkpoint);
	}

	public void reset() {
		PauseGate gate = pauseGate.getAndSet(null);
		if (gate != null) {
			gate.release();
		}
		failAt.set(null);
	}

	public static final class PauseGate implements AutoCloseable {

		private final Checkpoint checkpoint;
		private final AtomicBoolean claimed = new AtomicBoolean();
		private final CountDownLatch arrived = new CountDownLatch(1);
		private final CountDownLatch released = new CountDownLatch(1);
		private volatile long backendPid = -1;

		private PauseGate(Checkpoint checkpoint) {
			this.checkpoint = checkpoint;
		}

		/** Waits for the paused transaction and returns its backend PID. */
		public long awaitArrival(Duration timeout) throws InterruptedException {
			if (!arrived.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new AssertionError("No transaction reached " + checkpoint + " within " + timeout);
			}
			return backendPid;
		}

		public void release() {
			released.countDown();
		}

		@Override
		public void close() {
			release();
		}

		private void awaitRelease() {
			try {
				released.await(MAX_PAUSE.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}

	}

	/** Thrown at a checkpoint to model a crash (before commit) or a lost response (after commit). */
	public static final class InjectedFailure extends RuntimeException {

		public InjectedFailure(Checkpoint checkpoint) {
			super("Injected failure at " + checkpoint);
		}

	}

}
