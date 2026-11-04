package io.github.harsha135135.ledgercore.idempotency;

import io.github.harsha135135.ledgercore.api.ApiException;

/** Validates the Idempotency-Key header that every mutation must carry. */
public final class IdempotencyKeys {

	public static final String HEADER = "Idempotency-Key";

	private IdempotencyKeys() {
	}

	/** A key is 1 to {@code maxLength} visible ASCII characters (no spaces or control characters). */
	public static String require(String key, int maxLength) {
		if (key == null || key.isEmpty()) {
			throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED",
					"Send an Idempotency-Key header and reuse it when retrying this request.");
		}
		if (key.length() > maxLength) {
			throw invalid(maxLength);
		}
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if (c < 0x21 || c > 0x7E) {
				throw invalid(maxLength);
			}
		}
		return key;
	}

	private static ApiException invalid(int maxLength) {
		return ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
				"Idempotency-Key must be 1 to " + maxLength + " visible ASCII characters.");
	}

}
