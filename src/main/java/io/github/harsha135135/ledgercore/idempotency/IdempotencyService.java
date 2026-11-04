package io.github.harsha135135.ledgercore.idempotency;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.database.TransactionCheckpoints;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Makes a mutation happen at most once per (principal, operation scope, key).
 *
 * <p>Must be called inside the mutation's own transaction, after authorization and before any
 * account lock. The claim row, the business writes and the stored response then commit or roll
 * back together. There is no separately committed "pending" state that a crash could strand.
 */
@Service
public class IdempotencyService {

	private final JdbcTemplate jdbc;
	private final TransactionCheckpoints checkpoints;
	private final MeterRegistry meters;
	private final Counter replays;

	public IdempotencyService(JdbcTemplate jdbc, TransactionCheckpoints checkpoints, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.checkpoints = checkpoints;
		this.meters = meters;
		this.replays = Counter.builder("ledgercore.idempotency.replays")
			.description("Requests answered from a stored idempotent result")
			.register(meters);
	}

	/**
	 * Runs {@code work} if this request claims its key, then stores the result against the key.
	 * If the key is already complete with the same request hash, returns the stored result instead.
	 * Metrics are counted when the outcome is decided; a later commit failure would overcount slightly.
	 */
	public MutationResult runOnce(IdempotencyRequest request, Supplier<MutationResult> work) {
		Optional<MutationResult> stored = claimOrFindStored(request);
		if (stored.isPresent()) {
			replays.increment();
			return stored.get();
		}
		MutationResult outcome = work.get();
		complete(request, outcome);
		if (!outcome.isSuccess()) {
			meters.counter("ledgercore.business.rejections", "status", Integer.toString(outcome.httpStatus()))
				.increment();
		}
		checkpoints.reached(Checkpoint.BEFORE_COMMIT);
		return outcome;
	}

	/**
	 * Empty means this transaction now owns the key. A concurrent request with the same key waits
	 * on the uncommitted insert (bounded by lock_timeout) until this transaction ends.
	 */
	private Optional<MutationResult> claimOrFindStored(IdempotencyRequest request) {
		int inserted = jdbc.update(
				"INSERT INTO idempotency_requests (principal_id, operation_scope, key, request_hash, state) "
						+ "VALUES (?, ?, ?, ?, 'IN_PROGRESS') ON CONFLICT DO NOTHING",
				request.principalId(), request.operationScope(), request.key(), request.requestHash());
		if (inserted == 1) {
			checkpoints.reached(Checkpoint.AFTER_IDEMPOTENCY_CLAIM);
			return Optional.empty();
		}

		// The insert lost to a committed row. This must be a NEW statement: under READ COMMITTED it
		// takes a fresh snapshot that includes the winner, which committed after our insert began.
		List<StoredRequest> rows = jdbc.query(
				"SELECT request_hash, state, http_status, response_json FROM idempotency_requests "
						+ "WHERE principal_id = ? AND operation_scope = ? AND key = ?",
				(rs, rowNum) -> new StoredRequest(rs.getString("request_hash"), rs.getString("state"),
						rs.getInt("http_status"), rs.getString("response_json")),
				request.principalId(), request.operationScope(), request.key());
		if (rows.isEmpty()) {
			throw new IllegalStateException("Idempotency conflict without a visible row; refusing to continue.");
		}
		StoredRequest stored = rows.get(0);
		if (!stored.requestHash().equals(request.requestHash())) {
			throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
					"This Idempotency-Key was already used for a different request.");
		}
		if (!"COMPLETE".equals(stored.state())) {
			// A committed IN_PROGRESS row breaks the protocol. Never treat it as permission to rerun.
			throw new IllegalStateException("Committed IN_PROGRESS idempotency record found.");
		}
		return Optional.of(new MutationResult(stored.httpStatus(), stored.responseJson(), true));
	}

	private void complete(IdempotencyRequest request, MutationResult outcome) {
		int updated = jdbc.update(
				"UPDATE idempotency_requests SET state = 'COMPLETE', http_status = ?, response_json = ? "
						+ "WHERE principal_id = ? AND operation_scope = ? AND key = ? AND state = 'IN_PROGRESS'",
				outcome.httpStatus(), outcome.bodyJson(), request.principalId(), request.operationScope(),
				request.key());
		if (updated != 1) {
			throw new IllegalStateException("Could not complete the idempotency record this transaction claimed.");
		}
	}

	private record StoredRequest(String requestHash, String state, int httpStatus, String responseJson) {
	}

}
