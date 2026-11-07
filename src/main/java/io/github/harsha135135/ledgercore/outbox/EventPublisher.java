package io.github.harsha135135.ledgercore.outbox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import io.github.harsha135135.ledgercore.config.LedgerProperties;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Sends one event to the consumer over HTTP. Runs outside any database transaction and never
 * while account locks are held. A timeout does not prove the consumer failed, which is why the
 * consumer must deduplicate by event ID.
 */
@Component
public class EventPublisher {

	private final LedgerProperties.Outbox settings;
	private final Environment environment;
	private final HttpClient http;

	public EventPublisher(LedgerProperties properties, Environment environment) {
		this.settings = properties.outbox();
		this.environment = environment;
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(settings.deliveryTimeout())
			.build();
	}

	/** The exact bytes sent for an event. Built from stored values, so every attempt is identical. */
	public static String envelope(OutboxEvent event) {
		return "{\"eventId\":\"" + event.id() + "\",\"type\":\"" + event.type() + "\",\"schemaVersion\":"
				+ event.schemaVersion() + ",\"payload\":" + event.payloadJson() + "}";
	}

	public DeliveryResult send(OutboxEvent event) {
		HttpRequest request = HttpRequest.newBuilder(consumerUri())
			.timeout(settings.deliveryTimeout())
			.header("Content-Type", "application/json")
			.header("Authorization", basicAuthorization())
			.POST(HttpRequest.BodyPublishers.ofString(envelope(event), StandardCharsets.UTF_8))
			.build();
		try {
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
			int status = response.statusCode();
			if (status >= 200 && status < 300) {
				return DeliveryResult.success(status);
			}
			return DeliveryResult.failure("HTTP_" + status, status);
		}
		catch (HttpTimeoutException ex) {
			return DeliveryResult.failure("TIMEOUT", DeliveryResult.NO_RESPONSE);
		}
		catch (IOException ex) {
			return DeliveryResult.failure("CONNECTION_FAILED", DeliveryResult.NO_RESPONSE);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return DeliveryResult.failure("INTERRUPTED", DeliveryResult.NO_RESPONSE);
		}
	}

	private URI consumerUri() {
		if (settings.consumerUrl() != null && !settings.consumerUrl().isBlank()) {
			return URI.create(settings.consumerUrl());
		}
		String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
		return URI.create("http://127.0.0.1:" + port + "/demo-consumer/events");
	}

	private String basicAuthorization() {
		String token = settings.publisherUsername() + ":" + (settings.publisherPassword() == null ? ""
				: settings.publisherPassword());
		return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Acknowledged, or failed with a short error category stored on the event. The HTTP status is
	 * kept for diagnostics; it is {@link #NO_RESPONSE} when no response arrived.
	 */
	public record DeliveryResult(boolean acknowledged, String errorCode, int httpStatus) {

		public static final int NO_RESPONSE = 0;

		static DeliveryResult success(int httpStatus) {
			return new DeliveryResult(true, null, httpStatus);
		}

		static DeliveryResult failure(String errorCode, int httpStatus) {
			return new DeliveryResult(false, errorCode, httpStatus);
		}
	}

}
