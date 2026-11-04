package io.github.harsha135135.ledgercore.api;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the standard error body from filters and security handlers, which run outside controllers. */
@Component
public class ErrorResponseWriter {

	private final JsonText json;

	public ErrorResponseWriter(JsonText json) {
		this.json = json;
	}

	public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
			String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(json.write(new ErrorBody(code, message, RequestIds.of(request))));
	}

}
