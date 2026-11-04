package io.github.harsha135135.ledgercore.api;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict UUID parsing. {@link UUID#fromString} also accepts shortened forms such as "1-1-1-1-1",
 * so input must match the canonical 8-4-4-4-12 layout first.
 */
public final class Ids {

	private static final Pattern CANONICAL_UUID = Pattern
		.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private Ids() {
	}

	/** For identifiers in a request body: a malformed value is a 400. */
	public static UUID parseField(String raw, String fieldName) {
		if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
			throw ApiException.badRequest("INVALID_IDENTIFIER", fieldName + " must be a UUID.");
		}
		return UUID.fromString(raw);
	}

	/** For identifiers in a URL path: a malformed value cannot name an accessible resource, so it is a 404. */
	public static UUID parsePath(String raw) {
		if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
			throw ApiException.notFound();
		}
		return UUID.fromString(raw);
	}

}
