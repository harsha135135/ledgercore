package io.github.harsha135135.ledgercore.support;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import io.github.harsha135135.ledgercore.ledger.MutationResult;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Starts many calls together. The start barrier is released before any call asks the pool for a
 * connection, so it cannot deadlock the harness when callers outnumber pooled connections.
 * A barrier alone does not prove database overlap; the gate tests provide that evidence.
 */
public final class ConcurrentCalls {

	private ConcurrentCalls() {
	}

	public static <T> List<T> runTogether(int count, IntFunction<T> call, Duration timeout) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(count);
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int index = i;
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					return call.apply(index);
				}));
			}
			if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new AssertionError("Workers did not start in time");
			}
			start.countDown();
			long deadline = System.nanoTime() + timeout.toNanos();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				long remaining = Math.max(1, deadline - System.nanoTime());
				results.add(future.get(remaining, TimeUnit.NANOSECONDS));
			}
			return results;
		}
		finally {
			executor.shutdownNow();
		}
	}

	/**
	 * Retries a mutation after transient infrastructure failures, always with the same key, as a
	 * correct client must. Exhausting the budget makes the test inconclusive: an unresolved failure
	 * is never counted as a business outcome.
	 */
	public static Outcome withTransientRetry(Supplier<MutationResult> call, int maxAttempts) {
		List<String> transientFailures = new ArrayList<>();
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				return new Outcome(call.get(), transientFailures);
			}
			// DataAccessResourceFailureException also covers CannotGetJdbcConnectionException (pool timeout).
			catch (TransientDataAccessException | DataAccessResourceFailureException ex) {
				transientFailures.add(sqlState(ex));
				sleepQuietly(20L * attempt);
			}
		}
		throw new AssertionError("Inconclusive: transient failures not resolved within " + maxAttempts
				+ " attempts: " + transientFailures);
	}

	private static String sqlState(Throwable ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
			cause = cause.getCause();
		}
		return ex.getClass().getSimpleName();
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** A settled result plus the SQLSTATEs of transient failures retried on the way. */
	public record Outcome(MutationResult result, List<String> transientFailures) {
	}

}
