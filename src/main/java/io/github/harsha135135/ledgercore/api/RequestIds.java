package io.github.harsha135135.ledgercore.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

/** Reads the request ID assigned by {@link RequestIdFilter}. */
public final class RequestIds {

	static final String ATTRIBUTE = "ledgercore.requestId";
	public static final String HEADER = "X-Request-Id";

	private RequestIds() {
	}

	public static String of(HttpServletRequest request) {
		Object value = request.getAttribute(ATTRIBUTE);
		if (value instanceof String requestId) {
			return requestId;
		}
		// Only reachable if the filter did not run, for example in a misconfigured test.
		return UUID.randomUUID().toString();
	}

}
