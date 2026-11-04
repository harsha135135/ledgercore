package io.github.harsha135135.ledgercore.outbox;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.JsonText;
import io.github.harsha135135.ledgercore.audit.AuditRepository;

import org.springframework.stereotype.Component;

/**
 * Records a business mutation: one audit event and one PENDING outbox event, both inside the
 * caller's transaction. Using this for every mutation means no change can commit without its
 * event, and no event can exist for a change that rolled back.
 */
@Component
public class BusinessEvents {

	static final int SCHEMA_VERSION = 1;

	private final AuditRepository audit;
	private final OutboxRepository outbox;
	private final JsonText json;

	public BusinessEvents(AuditRepository audit, OutboxRepository outbox, JsonText json) {
		this.audit = audit;
		this.outbox = outbox;
		this.json = json;
	}

	public void record(UUID actorId, String action, String resourceType, UUID resourceId, String requestId,
			Map<String, String> details) {
		UUID auditEventId = audit.record(actorId, action, resourceType, resourceId, requestId, details);
		// Sorted keys make the stored payload easy to read; consumers should fetch current state anyway.
		Map<String, String> payload = new TreeMap<>(details);
		payload.put("resourceType", resourceType);
		payload.put("resourceId", resourceId.toString());
		String type = "ledger." + action.toLowerCase(Locale.ROOT);
		outbox.append(UUID.randomUUID(), auditEventId, type, SCHEMA_VERSION, json.write(payload));
	}

}
