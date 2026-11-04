package io.github.harsha135135.ledgercore.hold;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import io.github.harsha135135.ledgercore.api.Ids;
import io.github.harsha135135.ledgercore.api.MutationResponses;
import io.github.harsha135135.ledgercore.api.RequestIds;
import io.github.harsha135135.ledgercore.config.LedgerProperties;
import io.github.harsha135135.ledgercore.hold.HoldMessages.CreateHoldCommand;
import io.github.harsha135135.ledgercore.hold.HoldMessages.CreateHoldRequest;
import io.github.harsha135135.ledgercore.hold.HoldMessages.HoldResponse;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyKeys;
import io.github.harsha135135.ledgercore.security.PrincipalResolver;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP contract for holds. Capture and release carry no semantic body: the hold defines the amount. */
@RestController
@RequestMapping("/v1/holds")
public class HoldController {

	private final PrincipalResolver principalResolver;
	private final HoldService holdService;
	private final long maxAmountMinor;
	private final int maxIdempotencyKeyLength;

	public HoldController(PrincipalResolver principalResolver, HoldService holdService, LedgerProperties properties) {
		this.principalResolver = principalResolver;
		this.holdService = holdService;
		this.maxAmountMinor = properties.limits().maxAmountMinor();
		this.maxIdempotencyKeyLength = properties.limits().maxIdempotencyKeyLength();
	}

	@PostMapping
	public ResponseEntity<String> createHold(@RequestBody CreateHoldRequest body,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		CreateHoldCommand command = CreateHoldCommand.parse(body, maxAmountMinor);
		return MutationResponses.toResponse(holdService.createHold(callerId, command, key, RequestIds.of(http)));
	}

	@PostMapping("/{holdId}/capture")
	public ResponseEntity<String> capture(@PathVariable String holdId,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		return MutationResponses
			.toResponse(holdService.captureHold(callerId, Ids.parsePath(holdId), key, RequestIds.of(http)));
	}

	@PostMapping("/{holdId}/release")
	public ResponseEntity<String> release(@PathVariable String holdId,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		return MutationResponses
			.toResponse(holdService.releaseHold(callerId, Ids.parsePath(holdId), key, RequestIds.of(http)));
	}

	@GetMapping("/{holdId}")
	public HoldResponse getHold(@PathVariable String holdId, Authentication authentication, HttpServletRequest http) {
		UUID callerId = principalResolver.principalIdFor(authentication);
		return holdService.getHold(callerId, Ids.parsePath(holdId), RequestIds.of(http));
	}

}
