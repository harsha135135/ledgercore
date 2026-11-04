package io.github.harsha135135.ledgercore.outbox;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import io.github.harsha135135.ledgercore.api.Ids;
import io.github.harsha135135.ledgercore.api.MutationResponses;
import io.github.harsha135135.ledgercore.api.RequestIds;
import io.github.harsha135135.ledgercore.config.LedgerProperties;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyKeys;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.ReasonRequest;
import io.github.harsha135135.ledgercore.outbox.OutboxAdminService.OutboxPage;
import io.github.harsha135135.ledgercore.security.PrincipalResolver;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/outbox")
public class OutboxAdminController {

	private final PrincipalResolver principalResolver;
	private final OutboxAdminService outboxAdminService;
	private final int maxIdempotencyKeyLength;

	public OutboxAdminController(PrincipalResolver principalResolver, OutboxAdminService outboxAdminService,
			LedgerProperties properties) {
		this.principalResolver = principalResolver;
		this.outboxAdminService = outboxAdminService;
		this.maxIdempotencyKeyLength = properties.limits().maxIdempotencyKeyLength();
	}

	@GetMapping
	public OutboxPage list(@RequestParam(required = false) String state, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) String limit, Authentication authentication, HttpServletRequest http) {
		UUID callerId = principalResolver.principalIdFor(authentication);
		return outboxAdminService.listEvents(callerId, state, cursor, limit, RequestIds.of(http));
	}

	/** The body carries only a reason for the audit trail. */
	@PostMapping("/{eventId}/retry")
	public ResponseEntity<String> retry(@PathVariable String eventId, @RequestBody ReasonRequest body,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		String reason = LedgerRequests.requireReason(body.reason());
		return MutationResponses.toResponse(
				outboxAdminService.retryFailed(callerId, Ids.parsePath(eventId), reason, key, RequestIds.of(http)));
	}

}
