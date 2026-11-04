package io.github.harsha135135.ledgercore.api;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import io.github.harsha135135.ledgercore.config.LedgerProperties;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies above the configured limit (16 KiB by default) with 413.
 * Bodies here are small JSON documents, so reading at most limit + 1 bytes into memory
 * is simpler than a streaming counter and works when Content-Length is absent.
 */
@Component
@Order(0)
public class RequestBodyLimitFilter extends OncePerRequestFilter {

	private final int maxRequestBytes;
	private final ErrorResponseWriter errors;

	public RequestBodyLimitFilter(LedgerProperties properties, ErrorResponseWriter errors) {
		this.maxRequestBytes = properties.limits().maxRequestBytes();
		this.errors = errors;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!hasBody(request)) {
			chain.doFilter(request, response);
			return;
		}
		if (request.getContentLengthLong() > maxRequestBytes) {
			rejectTooLarge(request, response);
			return;
		}
		byte[] body = request.getInputStream().readNBytes(maxRequestBytes + 1);
		if (body.length > maxRequestBytes) {
			rejectTooLarge(request, response);
			return;
		}
		chain.doFilter(new CachedBodyRequest(request, body), response);
	}

	private static boolean hasBody(HttpServletRequest request) {
		String method = request.getMethod();
		return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
	}

	private void rejectTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
		errors.write(request, response, HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE",
				"The request body exceeds " + maxRequestBytes + " bytes.");
	}

	/** Replays a body that has already been read and size-checked. */
	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			InputStream source = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() throws IOException {
					return source.read();
				}

				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException {
					return source.read(buffer, offset, length);
				}

				@Override
				public boolean isFinished() {
					try {
						return source.available() == 0;
					}
					catch (IOException ex) {
						return true;
					}
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException("Asynchronous reads are not used.");
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}

	}

}
