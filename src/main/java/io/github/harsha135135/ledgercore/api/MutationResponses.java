package io.github.harsha135135.ledgercore.api;

import io.github.harsha135135.ledgercore.ledger.MutationResult;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Turns a mutation result into an HTTP response, writing the stored JSON exactly as recorded. */
public final class MutationResponses {

	public static final String REPLAY_HEADER = "Idempotent-Replay";

	private MutationResponses() {
	}

	public static ResponseEntity<String> toResponse(MutationResult result) {
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
			.contentType(MediaType.APPLICATION_JSON);
		if (result.replayed()) {
			builder.header(REPLAY_HEADER, "true");
		}
		return builder.body(result.bodyJson());
	}

}
