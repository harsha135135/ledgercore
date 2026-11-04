package io.github.harsha135135.ledgercore.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Identifies one logical request: who sent it, which operation (and target), the client's key,
 * and a hash of the validated semantic fields. Trace IDs and timestamps are deliberately not
 * hashed, so a genuine retry matches.
 */
public record IdempotencyRequest(UUID principalId, String operationScope, String key, String requestHash) {

	/**
	 * Builds the request with a SHA-256 hash of {@code name=value} lines in the given order.
	 * Callers pass the same fields in the same order every time.
	 */
	public static IdempotencyRequest of(UUID principalId, String operationScope, String key,
			String... namesAndValues) {
		if (namesAndValues.length % 2 != 0) {
			throw new IllegalArgumentException("Fields must be name/value pairs.");
		}
		StringBuilder canonical = new StringBuilder("scope=").append(operationScope);
		for (int i = 0; i < namesAndValues.length; i += 2) {
			canonical.append('\n').append(namesAndValues[i]).append('=').append(namesAndValues[i + 1]);
		}
		return new IdempotencyRequest(principalId, operationScope, key, sha256Hex(canonical.toString()));
	}

	static String sha256Hex(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every Java runtime", ex);
		}
	}

}
