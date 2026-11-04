package io.github.harsha135135.ledgercore.ledger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.api.Ids;

/**
 * An opaque page cursor: the (created_at, id) of the last entry returned. This is a stable display
 * order, not a global commit order, so live pages may miss writes that commit concurrently.
 */
public record JournalCursor(OffsetDateTime createdAt, UUID journalId) {

	public String encode() {
		String text = createdAt.toInstant().toString() + "|" + journalId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
	}

	public static JournalCursor decode(String encoded) {
		try {
			String text = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
			int separator = text.indexOf('|');
			if (separator < 0) {
				throw invalid();
			}
			Instant createdAt = Instant.parse(text.substring(0, separator));
			UUID journalId = Ids.parseField(text.substring(separator + 1), "cursor");
			return new JournalCursor(createdAt.atOffset(ZoneOffset.UTC), journalId);
		}
		catch (IllegalArgumentException | DateTimeParseException ex) {
			throw invalid();
		}
		catch (ApiException ex) {
			throw invalid();
		}
	}

	private static ApiException invalid() {
		return ApiException.badRequest("INVALID_CURSOR", "cursor is not a value returned by this API.");
	}

}
