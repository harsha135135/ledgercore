package io.github.harsha135135.ledgercore.audit;

import java.util.Map;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.JsonText;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Append-only audit trail, written in the same transaction as the change it describes. */
@Repository
public class AuditRepository {

	private final JdbcTemplate jdbc;
	private final JsonText json;

	public AuditRepository(JdbcTemplate jdbc, JsonText json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/** Returns the new audit event ID, which an outbox event can reference. */
	public UUID record(UUID actorId, String action, String resourceType, UUID resourceId, String requestId,
			Map<String, String> details) {
		UUID auditEventId = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO audit_events (id, actor_id, action, resource_type, resource_id, request_id, details_json) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
				auditEventId, actorId, action, resourceType, resourceId, requestId, json.write(details));
		return auditEventId;
	}

}
