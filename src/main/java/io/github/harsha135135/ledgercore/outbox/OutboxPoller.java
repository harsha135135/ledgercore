package io.github.harsha135135.ledgercore.outbox;

import java.time.Duration;
import java.util.List;

import io.github.harsha135135.ledgercore.config.LedgerProperties;
import io.github.harsha135135.ledgercore.outbox.EventPublisher.DeliveryResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The single outbox poller. Selects due PENDING events, sends each outside any transaction, then
 * records the attempt in a short transaction. Delivery is at least once: a crash after the
 * consumer acknowledges but before PUBLISHED is recorded causes a harmless redelivery.
 *
 * <p>Version one runs exactly one poller instance, so no claim or lease protocol is needed.
 */
@Component
public class OutboxPoller {

	private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

	private final OutboxRepository outbox;
	private final EventPublisher publisher;
	private final LedgerProperties.Outbox settings;

	public OutboxPoller(OutboxRepository outbox, EventPublisher publisher, LedgerProperties properties) {
		this.outbox = outbox;
		this.publisher = publisher;
		this.settings = properties.outbox();
	}

	@Scheduled(fixedDelayString = "${ledgercore.outbox.poll-interval}")
	void pollOnSchedule() {
		if (settings.pollerEnabled()) {
			pollOnce();
		}
	}

	/** One polling pass. Synchronized so a manual pass can never overlap the scheduled one. */
	public synchronized PollSummary pollOnce() {
		int stranded = outbox.failExhaustedPending(settings.maxAttempts());
		List<OutboxEvent> due = outbox.findDue(settings.maxAttempts(), settings.batchSize());
		int published = 0;
		int failed = 0;
		for (OutboxEvent event : due) {
			MDC.put("eventId", event.id().toString());
			try {
				DeliveryResult result = publisher.send(event);
				if (result.acknowledged()) {
					outbox.markPublished(event.id());
					published++;
				}
				else {
					int attemptNumber = event.attempts() + 1;
					outbox.recordFailure(event.id(), result.errorCode(), settings.maxAttempts(),
							backoffAfter(attemptNumber));
					failed++;
					log.warn("Outbox delivery attempt {} failed: {}", attemptNumber, result.errorCode());
				}
			}
			finally {
				MDC.remove("eventId");
			}
		}
		return new PollSummary(due.size(), published, failed, stranded);
	}

	/** Exponential backoff: base, 2 x base, 4 x base ... capped at the configured maximum. */
	Duration backoffAfter(int attemptNumber) {
		Duration delay = settings.backoffBase();
		for (int i = 1; i < attemptNumber; i++) {
			delay = delay.multipliedBy(2);
			if (delay.compareTo(settings.backoffMax()) >= 0) {
				return settings.backoffMax();
			}
		}
		return delay.compareTo(settings.backoffMax()) > 0 ? settings.backoffMax() : delay;
	}

	public record PollSummary(int selected, int published, int failed, int markedExhausted) {
	}

}
