package io.github.harsha135135.ledgercore.consumer;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import io.github.harsha135135.ledgercore.api.ErrorBody;
import io.github.harsha135135.ledgercore.api.Ids;
import io.github.harsha135135.ledgercore.api.RequestIds;
import io.github.harsha135135.ledgercore.consumer.DemoConsumer.Outcome;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The consumer's HTTP endpoint. Only the publisher's service credential may call it. The raw
 * body is hashed as received, so a redelivery of the same stored event always matches.
 */
@RestController
@RequestMapping("/demo-consumer")
public class DemoConsumerController {

	private final DemoConsumer consumer;
	private final JsonMapper mapper;

	public DemoConsumerController(DemoConsumer consumer, JsonMapper mapper) {
		this.consumer = consumer;
		this.mapper = mapper;
	}

	@PostMapping("/events")
	public ResponseEntity<Object> receive(@RequestBody String rawBody, HttpServletRequest http) {
		UUID eventId;
		String eventType;
		try {
			JsonNode envelope = mapper.readTree(rawBody);
			eventId = Ids.parseField(envelope.path("eventId").asString(null), "eventId");
			eventType = envelope.path("type").asString(null);
		}
		catch (JacksonException ex) {
			return error(HttpStatus.BAD_REQUEST, "MALFORMED_EVENT", "The event is not valid JSON.", http);
		}
		if (eventType == null || eventType.isBlank()) {
			return error(HttpStatus.BAD_REQUEST, "MALFORMED_EVENT", "The event has no type.", http);
		}
		Outcome outcome = consumer.receive(eventId, eventType, rawBody);
		return switch (outcome) {
			case APPLIED, DUPLICATE -> ResponseEntity.ok(Map.of("eventId", eventId.toString(), "status", outcome.name()));
			case PAYLOAD_MISMATCH -> error(HttpStatus.CONFLICT, "PAYLOAD_MISMATCH",
					"This event ID was already received with a different payload.", http);
			case UNAVAILABLE -> error(HttpStatus.SERVICE_UNAVAILABLE, "CONSUMER_UNAVAILABLE",
					"The consumer is not accepting events.", http);
		};
	}

	/** Demonstration control: simulate a downstream outage, or end it. */
	@PostMapping("/availability")
	public Map<String, Object> setAvailability(@RequestParam boolean accepting) {
		consumer.setAccepting(accepting);
		return Map.of("accepting", consumer.isAccepting());
	}

	@GetMapping("/counts")
	public Map<String, Object> counts(@RequestParam String eventType) {
		return Map.of("eventType", eventType, "applied", consumer.appliedCount(eventType));
	}

	private static ResponseEntity<Object> error(HttpStatus status, String code, String message,
			HttpServletRequest http) {
		return ResponseEntity.status(status).body(new ErrorBody(code, message, RequestIds.of(http)));
	}

}
