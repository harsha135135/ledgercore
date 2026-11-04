package io.github.harsha135135.ledgercore.hold;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A hold as stored. Source, destination and amount are fixed when the hold is created. */
public record HoldRow(
		UUID id,
		UUID sourceId,
		UUID destinationId,
		long amountMinor,
		HoldState state,
		UUID captureJournalId,
		OffsetDateTime createdAt,
		OffsetDateTime closedAt) {
}
