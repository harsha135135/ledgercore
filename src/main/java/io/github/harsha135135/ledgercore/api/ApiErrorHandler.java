package io.github.harsha135135.ledgercore.api;

import jakarta.servlet.http.HttpServletRequest;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one place where exceptions become HTTP errors. Bodies never contain SQL, stack traces
 * or credentials.
 */
@RestControllerAdvice
public class ApiErrorHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

	private final MeterRegistry meters;

	public ApiErrorHandler(MeterRegistry meters) {
		this.meters = meters;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ErrorBody> handleApiException(ApiException ex, HttpServletRequest request) {
		return error(ex.status(), ex.code(), ex.getMessage(), request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorBody> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
				"The request body is missing, is not valid JSON, or contains unknown, duplicated or wrongly typed fields.",
				request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ErrorBody> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
		return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This method is not supported here.",
				request);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorBody> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
		return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send application/json.", request);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ErrorBody> handleNoRoute(NoResourceFoundException ex, HttpServletRequest request) {
		ApiException notFound = ApiException.notFound();
		return error(notFound.status(), notFound.code(), notFound.getMessage(), request);
	}

	/**
	 * Lock timeouts, deadlock victims, serialization failures, pool exhaustion and lost connections.
	 * The transaction rolled back, or its commit outcome is unknown, so the client must retry with
	 * the same Idempotency-Key and never a new one.
	 */
	@ExceptionHandler({ TransientDataAccessException.class, CannotGetJdbcConnectionException.class,
			DataAccessResourceFailureException.class, TransactionTimedOutException.class })
	ResponseEntity<ErrorBody> handleTransient(Exception ex, HttpServletRequest request) {
		// CannotAcquireLockException is a lock timeout; PessimisticLockingFailureException covers deadlock victims.
		meters.counter("ledgercore.transient.failures", "type", ex.getClass().getSimpleName()).increment();
		log.warn("Transient database failure: {}", ex.getClass().getSimpleName());
		return error(HttpStatus.SERVICE_UNAVAILABLE, "TRANSIENT_FAILURE",
				"Temporary infrastructure failure. Retry with the same Idempotency-Key.", request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorBody> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unexpected failure", ex);
		return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"Unexpected server error. If this was a mutation, retry with the same Idempotency-Key.", request);
	}

	private static ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message,
			HttpServletRequest request) {
		return ResponseEntity.status(status).body(new ErrorBody(code, message, RequestIds.of(request)));
	}

}
