package io.github.harsha135135.ledgercore.ledger;

import io.github.harsha135135.ledgercore.api.ErrorBody;
import io.github.harsha135135.ledgercore.api.JsonText;

import org.springframework.http.HttpStatus;

/**
 * The outcome of a mutation: an HTTP status and the exact JSON body. Successes and deterministic
 * business rejections (such as insufficient funds) are both results. Only results are stored
 * against an idempotency key; unexpected exceptions roll back instead.
 */
public record MutationResult(int httpStatus, String bodyJson, boolean replayed) {

	public static MutationResult of(HttpStatus status, Object body, JsonText json) {
		return new MutationResult(status.value(), json.write(body), false);
	}

	/** A business rejection decided from locked rows. Nothing financial was changed. */
	public static MutationResult rejected(HttpStatus status, String code, String message, String requestId,
			JsonText json) {
		return of(status, new ErrorBody(code, message, requestId), json);
	}

	public boolean isSuccess() {
		return httpStatus >= 200 && httpStatus < 300;
	}

}
