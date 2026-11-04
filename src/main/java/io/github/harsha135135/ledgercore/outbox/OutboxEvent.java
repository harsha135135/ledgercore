package io.github.harsha135135.ledgercore.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One stored outbox event and its delivery bookkeeping. */
public record OutboxEvent(
		UUID id,
		UUID auditEventId,
		String type,
		int schemaVersion,
		String payloadJson,
		OutboxState state,
		int attempts,
		int totalAttempts,
		OffsetDateTime nextAttemptAt,
		OffsetDateTime publishedAt,
		String lastErrorCode,
		OffsetDateTime createdAt) {

	public enum OutboxState {
		PENDING, PUBLISHED, FAILED
	}

}
