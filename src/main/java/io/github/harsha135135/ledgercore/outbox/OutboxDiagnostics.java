package io.github.harsha135135.ledgercore.outbox;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import io.github.harsha135135.ledgercore.config.LedgerProperties;
import io.github.harsha135135.ledgercore.outbox.EventPublisher.DeliveryResult;
import io.github.harsha135135.ledgercore.outbox.OutboxPoller.PollSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Diagnostic records for outbox delivery: one structured log record per delivery attempt and per
 * non-empty polling pass, plus two timers. Records carry identifiers, times and result categories,
 * never the event payload.
 *
 * <p>The poller calls these methods after its database update. They never throw: a diagnostics
 * problem must not change what is delivered or recorded.
 */
@Component
public class OutboxDiagnostics {

	/** Logger for diagnostic records. Its level is off unless diagnostics recording is enabled. */
	public static final String RECORD_LOGGER = "ledgercore.diagnostics";

	private static final Logger records = LoggerFactory.getLogger(RECORD_LOGGER);
	private static final Logger log = LoggerFactory.getLogger(OutboxDiagnostics.class);

	private final MeterRegistry registry;
	private final LedgerProperties.Outbox settings;
	private final Timer pollTimer;

	public OutboxDiagnostics(MeterRegistry registry, LedgerProperties properties) {
		this.registry = registry;
		this.settings = properties.outbox();
		this.pollTimer = Timer.builder("ledgercore.outbox.poll")
			.description("Duration of one outbox polling pass, including idle passes")
			.register(registry);
	}

	/** One delivery attempt, recorded after its database update. */
	void deliveryAttempt(OutboxEvent event, Instant startedAt, long durationNanos, DeliveryResult result,
			boolean transitionRecorded) {
		try {
			Timer.builder("ledgercore.outbox.delivery")
				.description("Duration of one outbox delivery attempt, by result")
				.tag("result", timerResult(result))
				.register(registry)
				.record(durationNanos, TimeUnit.NANOSECONDS);
			records.atInfo()
				.addKeyValue("record", "outbox.delivery_attempt")
				// Not "eventId": the poller already puts eventId in the MDC, and the JSON file format writes
				// MDC entries and key-value pairs side by side, so a second "eventId" would make the
				// encoder drop the whole record.
				.addKeyValue("outboxEventId", event.id().toString())
				.addKeyValue("attempt", event.attempts() + 1)
				.addKeyValue("totalAttempt", event.totalAttempts() + 1)
				.addKeyValue("startedAt", startedAt.toString())
				.addKeyValue("endedAt", startedAt.plusNanos(durationNanos).toString())
				.addKeyValue("durationMs", millis(durationNanos))
				.addKeyValue("result", result.acknowledged() ? "SUCCESS" : result.errorCode())
				.addKeyValue("httpStatus", result.httpStatus())
				// False when the consumer acknowledged but the event was no longer PENDING (an operator
				// changed it meanwhile), so no PUBLISHED transition was recorded by this attempt.
				.addKeyValue("transitionRecorded", transitionRecorded)
				.log("Outbox delivery attempt");
		}
		catch (RuntimeException ex) {
			log.warn("Could not record an outbox delivery diagnostic: {}", ex.toString());
		}
	}

	/**
	 * One polling pass. Every pass is timed; only passes that selected or failed events are logged, so
	 * an idle poller does not write a record every second.
	 */
	void pollPass(Instant startedAt, long durationNanos, PollSummary summary) {
		try {
			pollTimer.record(durationNanos, TimeUnit.NANOSECONDS);
			if (summary.selected() == 0 && summary.markedExhausted() == 0) {
				return;
			}
			records.atInfo()
				.addKeyValue("record", "outbox.poll_batch")
				.addKeyValue("startedAt", startedAt.toString())
				.addKeyValue("endedAt", startedAt.plusNanos(durationNanos).toString())
				.addKeyValue("durationMs", millis(durationNanos))
				.addKeyValue("selected", summary.selected())
				.addKeyValue("published", summary.published())
				.addKeyValue("failed", summary.failed())
				.addKeyValue("markedExhausted", summary.markedExhausted())
				.addKeyValue("batchSize", settings.batchSize())
				.addKeyValue("configuredDelayMs", settings.pollInterval().toMillis())
				.log("Outbox poll batch");
		}
		catch (RuntimeException ex) {
			log.warn("Could not record an outbox poll diagnostic: {}", ex.toString());
		}
	}

	/** A small, fixed set of timer tag values, so the metric never gains unbounded labels. */
	static String timerResult(DeliveryResult result) {
		if (result.acknowledged()) {
			return "success";
		}
		int status = result.httpStatus();
		if (status == 401 || status == 403) {
			return "auth_rejected";
		}
		if (status >= 500) {
			return "http_5xx";
		}
		if (status >= 400) {
			return "http_4xx";
		}
		if (status != DeliveryResult.NO_RESPONSE) {
			return "http_other";
		}
		return switch (result.errorCode()) {
			case "TIMEOUT" -> "timeout";
			case "CONNECTION_FAILED" -> "connection_failed";
			default -> "other";
		};
	}

	/** Milliseconds with microsecond precision. */
	private static double millis(long nanos) {
		return Math.round(nanos / 1_000.0) / 1_000.0;
	}

}
