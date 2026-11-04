package io.github.harsha135135.ledgercore.api;

/** The single error format: a stable machine-readable code, a safe message, and the request ID. */
public record ErrorBody(String code, String message, String requestId) {
}
