package io.github.harsha135135.ledgercore.ledger;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import io.github.harsha135135.ledgercore.api.Ids;
import io.github.harsha135135.ledgercore.api.MutationResponses;
import io.github.harsha135135.ledgercore.api.RequestIds;
import io.github.harsha135135.ledgercore.config.LedgerProperties;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyKeys;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.CreateAccountRequest;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.FundingCommand;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.FundingRequest;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.ReasonRequest;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferRequest;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.AccountResponse;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.BalanceResponse;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalPageResponse;
import io.github.harsha135135.ledgercore.ledger.ReconciliationService.ReconciliationReport;
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

/** HTTP contract for accounts, funding, transfers and reconciliation. Parses input, then calls a service. */
@RestController
@RequestMapping("/v1")
public class LedgerController {

	private final PrincipalResolver principalResolver;
	private final AccountService accountService;
	private final LedgerService ledgerService;
	private final ReconciliationService reconciliationService;
	private final ReversalService reversalService;
	private final long maxAmountMinor;
	private final int maxIdempotencyKeyLength;

	public LedgerController(PrincipalResolver principalResolver, AccountService accountService,
			LedgerService ledgerService, ReconciliationService reconciliationService, ReversalService reversalService,
			LedgerProperties properties) {
		this.principalResolver = principalResolver;
		this.accountService = accountService;
		this.ledgerService = ledgerService;
		this.reconciliationService = reconciliationService;
		this.reversalService = reversalService;
		this.maxAmountMinor = properties.limits().maxAmountMinor();
		this.maxIdempotencyKeyLength = properties.limits().maxIdempotencyKeyLength();
	}

	@PostMapping("/accounts")
	public ResponseEntity<String> createAccount(@RequestBody CreateAccountRequest body,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		UUID ownerId = Ids.parseField(body.ownerId(), "ownerId");
		return MutationResponses
			.toResponse(accountService.createAccount(callerId, ownerId, key, RequestIds.of(http)));
	}

	@PostMapping("/demo-funding")
	public ResponseEntity<String> fund(@RequestBody FundingRequest body,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		FundingCommand command = FundingCommand.parse(body, maxAmountMinor);
		return MutationResponses.toResponse(ledgerService.fund(callerId, command, key, RequestIds.of(http)));
	}

	@PostMapping("/transfers")
	public ResponseEntity<String> transfer(@RequestBody TransferRequest body,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		TransferCommand command = TransferCommand.parse(body, maxAmountMinor);
		return MutationResponses.toResponse(ledgerService.transfer(callerId, command, key, RequestIds.of(http)));
	}

	@GetMapping("/accounts/{accountId}")
	public AccountResponse getAccount(@PathVariable String accountId, Authentication authentication,
			HttpServletRequest http) {
		UUID callerId = principalResolver.principalIdFor(authentication);
		return accountService.getAccount(callerId, Ids.parsePath(accountId), RequestIds.of(http));
	}

	@GetMapping("/accounts/{accountId}/balance")
	public BalanceResponse getBalance(@PathVariable String accountId, Authentication authentication,
			HttpServletRequest http) {
		UUID callerId = principalResolver.principalIdFor(authentication);
		return accountService.getBalance(callerId, Ids.parsePath(accountId), RequestIds.of(http));
	}

	@GetMapping("/accounts/{accountId}/journals")
	public JournalPageResponse listJournals(@PathVariable String accountId,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) String limit,
			Authentication authentication, HttpServletRequest http) {
		UUID callerId = principalResolver.principalIdFor(authentication);
		return accountService.listJournals(callerId, Ids.parsePath(accountId), cursor, limit, RequestIds.of(http));
	}

	@PostMapping("/journals/{journalId}/reversal")
	public ResponseEntity<String> reverse(@PathVariable String journalId, @RequestBody ReasonRequest body,
			@RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
			Authentication authentication, HttpServletRequest http) {
		String key = IdempotencyKeys.require(idempotencyKey, maxIdempotencyKeyLength);
		UUID callerId = principalResolver.principalIdFor(authentication);
		String reason = LedgerRequests.requireReason(body.reason());
		return MutationResponses.toResponse(
				reversalService.reverse(callerId, Ids.parsePath(journalId), reason, key, RequestIds.of(http)));
	}

	@GetMapping("/admin/reconciliation")
	public ReconciliationReport reconcile(Authentication authentication, HttpServletRequest http) {
		UUID callerId = principalResolver.principalIdFor(authentication);
		return reconciliationService.reconcileAs(callerId, RequestIds.of(http));
	}

}
