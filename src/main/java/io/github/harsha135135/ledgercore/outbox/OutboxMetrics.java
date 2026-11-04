package io.github.harsha135135.ledgercore.outbox;

import io.github.harsha135135.ledgercore.outbox.OutboxEvent.OutboxState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

/**
 * Makes undelivered events visible: an exhausted-event count that should alert an operator, and
 * the age of the oldest event not yet published. Each value is read from PostgreSQL when scraped.
 */
@Component
public class OutboxMetrics {

	public OutboxMetrics(MeterRegistry registry, OutboxRepository outbox) {
		Gauge.builder("ledgercore.outbox.failed.events", outbox, repository -> repository.countByState(OutboxState.FAILED))
			.description("Outbox events that exhausted automatic retries and need operator action")
			.register(registry);
		Gauge.builder("ledgercore.outbox.pending.events", outbox,
				repository -> repository.countByState(OutboxState.PENDING))
			.description("Outbox events waiting for delivery")
			.register(registry);
		Gauge.builder("ledgercore.outbox.oldest.undelivered.age", outbox, OutboxRepository::oldestUndeliveredAgeSeconds)
			.description("Age of the oldest event not yet published")
			.baseUnit("seconds")
			.register(registry);
	}

}
