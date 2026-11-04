package io.github.harsha135135.ledgercore.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.api.JsonText;
import io.github.harsha135135.ledgercore.audit.AuditRepository;
import io.github.harsha135135.ledgercore.config.LedgerProperties;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.database.LedgerTransactions;
import io.github.harsha135135.ledgercore.database.TransactionCheckpoints;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyRequest;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyService;
import io.github.harsha135135.ledgercore.ledger.JournalCursor;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.github.harsha135135.ledgercore.outbox.OutboxEvent.OutboxState;
import io.github.harsha135135.ledgercore.security.Permissions;
import io.github.harsha135135.ledgercore.security.PrincipalRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Operator view and recovery for outbox events. Exhausted events are never deleted; an ADMIN can
 * requeue a FAILED event with a reason. Requeueing keeps the event ID and total attempts and does
 * not recreate the business operation.
 */
@Service
public class OutboxAdminService {

	private final PrincipalRepository principals;
	private final OutboxRepository outbox;
	private final AuditRepository audit;
	private final IdempotencyService idempotency;
	private final LedgerTransactions transactions;
	private final TransactionCheckpoints checkpoints;
	private final JsonText json;
	private final LedgerProperties.Limits limits;

	public OutboxAdminService(PrincipalRepository principals, OutboxRepository outbox, AuditRepository audit,
			IdempotencyService idempotency, LedgerTransactions transactions, TransactionCheckpoints checkpoints,
			JsonText json, LedgerProperties properties) {
		this.principals = principals;
		this.outbox = outbox;
		this.audit = audit;
		this.idempotency = idempotency;
		this.transactions = transactions;
		this.checkpoints = checkpoints;
		this.json = json;
		this.limits = properties.limits();
	}

	public OutboxPage listEvents(UUID callerId, String stateText, String cursor, String limitText, String requestId) {
		Permissions.requireAdmin(principals.requireCurrent(callerId));
		OutboxState state = parseState(stateText);
		int pageSize = parsePageSize(limitText);
		JournalCursor after = cursor == null || cursor.isEmpty() ? null : JournalCursor.decode(cursor);
		List<OutboxEvent> rows = outbox.findPage(state, after, pageSize + 1);
		List<OutboxEventResponse> items = new ArrayList<>();
		for (int i = 0; i < Math.min(rows.size(), pageSize); i++) {
			items.add(OutboxEventResponse.from(rows.get(i), null));
		}
		String nextCursor = null;
		if (rows.size() > pageSize) {
			OutboxEvent last = rows.get(pageSize - 1);
			nextCursor = new JournalCursor(last.createdAt(), last.id()).encode();
		}
		return new OutboxPage(state.name(), items, nextCursor, requestId);
	}

	public MutationResult retryFailed(UUID callerId, UUID eventId, String reason, String idempotencyKey,
			String requestId) {
		Permissions.requireAdmin(principals.requireCurrent(callerId));
		if (outbox.findById(eventId).isEmpty()) {
			throw ApiException.notFound();
		}
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "OUTBOX_RETRY:" + eventId, idempotencyKey,
				"eventId", eventId.toString(), "reason", reason);

		MutationResult result = transactions.inMutationTransaction(() -> {
			Permissions.requireAdmin(principals.requireCurrent(callerId));
			return idempotency.runOnce(keyRequest, () -> {
				OutboxEvent locked = outbox.lockById(eventId);
				if (locked.state() != OutboxState.FAILED) {
					return MutationResult.rejected(HttpStatus.CONFLICT, "EVENT_NOT_FAILED",
							"Only FAILED events can be retried manually.", requestId, json);
				}
				OutboxEvent requeued = outbox.requeue(eventId);
				audit.record(callerId, "OUTBOX_RETRY_REQUESTED", "OUTBOX_EVENT", eventId, requestId,
						Map.of("reason", reason, "totalAttempts", Integer.toString(requeued.totalAttempts())));
				return MutationResult.of(HttpStatus.OK, OutboxEventResponse.from(requeued, requestId), json);
			});
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	private static OutboxState parseState(String stateText) {
		if (stateText == null || stateText.isEmpty()) {
			return OutboxState.FAILED;
		}
		try {
			return OutboxState.valueOf(stateText);
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.badRequest("INVALID_STATE", "state must be PENDING, PUBLISHED or FAILED.");
		}
	}

	private int parsePageSize(String limitText) {
		if (limitText == null || limitText.isEmpty()) {
			return limits.defaultPageSize();
		}
		if (!limitText.matches("^[1-9][0-9]{0,3}$") || Integer.parseInt(limitText) > limits.maxPageSize()) {
			throw ApiException.badRequest("INVALID_LIMIT",
					"limit must be a whole number from 1 to " + limits.maxPageSize() + ".");
		}
		return Integer.parseInt(limitText);
	}

	/** Event metadata only; the payload is not exposed through the admin view. */
	public record OutboxEventResponse(String eventId, String type, String state, int attempts, int totalAttempts,
			String lastErrorCode, String createdAt, String nextAttemptAt, String publishedAt, String requestId) {

		static OutboxEventResponse from(OutboxEvent event, String requestId) {
			return new OutboxEventResponse(event.id().toString(), event.type(), event.state().name(), event.attempts(),
					event.totalAttempts(), event.lastErrorCode(), LedgerResponses.timestamp(event.createdAt()),
					LedgerResponses.timestamp(event.nextAttemptAt()), LedgerResponses.timestamp(event.publishedAt()),
					requestId);
		}
	}

	public record OutboxPage(String state, List<OutboxEventResponse> items, String nextCursor, String requestId) {
	}

}
