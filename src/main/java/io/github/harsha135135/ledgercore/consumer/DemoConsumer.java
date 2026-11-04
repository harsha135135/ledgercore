package io.github.harsha135135.ledgercore.consumer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.database.LedgerTransactions;
import io.github.harsha135135.ledgercore.database.TransactionCheckpoints;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * A stand-in for a downstream system. It inserts the event ID into its inbox and applies its
 * effect (a count per event type) in the same transaction, so a redelivered event is acknowledged
 * without being applied twice. A different payload under a known event ID is an error.
 *
 * <p>Deduplication only protects effects committed atomically with the inbox row; it cannot make
 * an unrelated external side effect exactly-once.
 */
@Service
public class DemoConsumer {

	private final JdbcTemplate jdbc;
	private final LedgerTransactions transactions;
	private final TransactionCheckpoints checkpoints;
	/** Lets a demonstration or test simulate an outage of the downstream system. */
	private final AtomicBoolean accepting = new AtomicBoolean(true);

	public DemoConsumer(JdbcTemplate jdbc, LedgerTransactions transactions, TransactionCheckpoints checkpoints) {
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.checkpoints = checkpoints;
	}

	public Outcome receive(UUID eventId, String eventType, String rawBody) {
		if (!accepting.get()) {
			return Outcome.UNAVAILABLE;
		}
		String payloadHash = sha256Hex(rawBody);
		Outcome outcome = transactions.inMutationTransaction(() -> {
			int inserted = jdbc.update("INSERT INTO consumer_inbox (event_id, event_type, payload_hash) "
					+ "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", eventId, eventType, payloadHash);
			if (inserted == 1) {
				jdbc.update("INSERT INTO consumer_event_counts (event_type, applied) VALUES (?, 1) "
						+ "ON CONFLICT (event_type) DO UPDATE SET applied = consumer_event_counts.applied + 1",
						eventType);
				return Outcome.APPLIED;
			}
			List<String> stored = jdbc.queryForList("SELECT payload_hash FROM consumer_inbox WHERE event_id = ?",
					String.class, eventId);
			return stored.get(0).equals(payloadHash) ? Outcome.DUPLICATE : Outcome.PAYLOAD_MISMATCH;
		});
		// A failure injected here models a consumer crash after commit, before it acknowledges.
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return outcome;
	}

	public void setAccepting(boolean accepting) {
		this.accepting.set(accepting);
	}

	public boolean isAccepting() {
		return accepting.get();
	}

	public long appliedCount(String eventType) {
		List<Long> counts = jdbc.queryForList("SELECT applied FROM consumer_event_counts WHERE event_type = ?",
				Long.class, eventType);
		return counts.isEmpty() ? 0 : counts.get(0);
	}

	public boolean hasReceived(UUID eventId) {
		Integer count = jdbc.queryForObject("SELECT count(*) FROM consumer_inbox WHERE event_id = ?", Integer.class,
				eventId);
		return count != null && count > 0;
	}

	private static String sha256Hex(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public enum Outcome {
		APPLIED, DUPLICATE, PAYLOAD_MISMATCH, UNAVAILABLE
	}

}
