package io.github.harsha135135.ledgercore.api;

import org.springframework.http.HttpStatus;

/**
 * A request rejected before any financial state changed. Thrown outside or at the start of a
 * transaction; the API boundary turns it into the standard error body.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public static ApiException badRequest(String code, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, message);
	}

	/** The same response for a missing resource and one the caller may not inspect. */
	public static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
				"The resource does not exist or is not accessible.");
	}

	/** A role-level denial, such as a customer calling an administrator endpoint. */
	public static ApiException forbidden() {
		return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN_OPERATION",
				"Your role does not permit this operation.");
	}

	public static ApiException unprocessable(String code, String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
	}

}
