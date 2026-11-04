package io.github.harsha135135.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.api.JsonText;
import io.github.harsha135135.ledgercore.outbox.BusinessEvents;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.database.LedgerTransactions;
import io.github.harsha135135.ledgercore.database.TransactionCheckpoints;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyRequest;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyService;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.FundingCommand;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalResponse;
import io.github.harsha135135.ledgercore.security.Permissions;
import io.github.harsha135135.ledgercore.security.PrincipalRepository;
import io.github.harsha135135.ledgercore.security.PrincipalRow;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Money movements between accounts: demo funding and customer transfers.
 *
 * <p>Each method follows the same protocol:
 * <ol>
 * <li>Read the caller's current role and check immutable ownership with ordinary reads.
 * Forbidden requests stop here, before claiming a key or taking any lock.</li>
 * <li>Start a READ COMMITTED transaction and re-read the role.</li>
 * <li>Claim the idempotency key, or return the stored result of an earlier identical request.</li>
 * <li>Lock the affected accounts through {@link AccountLocks}.</li>
 * <li>Check state and funds using only the locked rows.</li>
 * <li>Write the journal, both balance projections, the audit event and the stored response;
 * commit; only then respond.</li>
 * </ol>
 */
@Service
public class LedgerService {

	private final PrincipalRepository principals;
	private final AccountRepository accounts;
	private final AccountLocks accountLocks;
	private final JournalPoster journalPoster;
	private final BusinessEvents events;
	private final IdempotencyService idempotency;
	private final LedgerTransactions transactions;
	private final TransactionCheckpoints checkpoints;
	private final JsonText json;

	public LedgerService(PrincipalRepository principals, AccountRepository accounts, AccountLocks accountLocks,
			JournalPoster journalPoster, BusinessEvents events, IdempotencyService idempotency,
			LedgerTransactions transactions, TransactionCheckpoints checkpoints, JsonText json) {
		this.principals = principals;
		this.accounts = accounts;
		this.accountLocks = accountLocks;
		this.journalPoster = journalPoster;
		this.events = events;
		this.idempotency = idempotency;
		this.transactions = transactions;
		this.checkpoints = checkpoints;
		this.json = json;
	}

	/** ADMIN-only synthetic funding: debit the system control account, credit a customer. */
	public MutationResult fund(UUID callerId, FundingCommand command, String idempotencyKey, String requestId) {
		Permissions.requireAdmin(principals.requireCurrent(callerId));
		AccountRow destination = requireCustomerAccount(command.destinationAccountId());
		UUID controlId = accounts.findSystemControlId();
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "DEMO_FUNDING", idempotencyKey,
				"destinationAccountId", destination.id().toString(), "amountMinor",
				Money.format(command.amountMinor()), "currency", Money.CURRENCY, "reason", command.reason());

		MutationResult result = transactions.inMutationTransaction(() -> {
			// Re-read the role inside the transaction. A demoted administrator is refused here,
			// before the key lookup, so a stored success is never disclosed to them.
			Permissions.requireAdmin(principals.requireCurrent(callerId));
			return idempotency.runOnce(keyRequest,
					() -> postFunding(callerId, controlId, destination.id(), command, requestId));
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	/** A customer moves money from an account they own to another customer account. */
	public MutationResult transfer(UUID callerId, TransferCommand command, String idempotencyKey,
			String requestId) {
		PrincipalRow caller = principals.requireCurrent(callerId);
		AccountRow source = accounts.findById(command.sourceAccountId()).orElseThrow(ApiException::notFound);
		// Ownership is immutable, so this ordinary read is safe. ADMIN does not bypass it.
		if (!source.isCustomerOwnedBy(caller.id())) {
			throw ApiException.notFound();
		}
		AccountRow destination = requireCustomerAccount(command.destinationAccountId());
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "TRANSFER", idempotencyKey,
				"sourceAccountId", source.id().toString(), "destinationAccountId", destination.id().toString(),
				"amountMinor", Money.format(command.amountMinor()), "currency", Money.CURRENCY);

		MutationResult result = transactions.inMutationTransaction(() -> {
			principals.requireCurrent(callerId);
			return idempotency.runOnce(keyRequest,
					() -> postTransfer(callerId, source.id(), destination.id(), command.amountMinor(), requestId));
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	private MutationResult postFunding(UUID callerId, UUID controlId, UUID destinationId, FundingCommand command,
			String requestId) {
		long amount = command.amountMinor();
		Map<UUID, AccountRow> locked = accountLocks.lockInCanonicalOrder(List.of(controlId, destinationId));
		checkpoints.reached(Checkpoint.AFTER_ACCOUNT_LOCKS);
		AccountRow control = locked.get(controlId);
		AccountRow destination = locked.get(destinationId);

		if (!control.isActive() || !destination.isActive()) {
			return accountNotActive(requestId);
		}
		long newControlBalance;
		long newDestinationBalance;
		try {
			newControlBalance = Math.subtractExact(control.creditBalanceMinor(), amount);
			newDestinationBalance = Math.addExact(destination.creditBalanceMinor(), amount);
		}
		catch (ArithmeticException overflow) {
			return balanceLimitExceeded(requestId);
		}

		UUID journalId = UUID.randomUUID();
		OffsetDateTime createdAt = journalPoster.post(journalId, JournalKind.FUNDING, amount, callerId, null,
				control.id(), destination.id());
		accounts.updateBalances(control.id(), newControlBalance, control.reservedMinor());
		accounts.updateBalances(destination.id(), newDestinationBalance, destination.reservedMinor());
		events.record(callerId, "DEMO_FUNDING_POSTED", "JOURNAL", journalId, requestId,
				Map.of("destinationAccountId", destination.id().toString(), "amountMinor", Money.format(amount),
						"reason", command.reason()));
		return journalCreated(journalId, JournalKind.FUNDING, amount, createdAt, requestId);
	}

	private MutationResult postTransfer(UUID callerId, UUID sourceId, UUID destinationId, long amount,
			String requestId) {
		Map<UUID, AccountRow> locked = accountLocks.lockInCanonicalOrder(List.of(sourceId, destinationId));
		checkpoints.reached(Checkpoint.AFTER_ACCOUNT_LOCKS);
		AccountRow source = locked.get(sourceId);
		AccountRow destination = locked.get(destinationId);

		// Defensive recheck against the locked row. Throwing rolls back the key claim too.
		if (!source.isCustomerOwnedBy(callerId)) {
			throw ApiException.notFound();
		}
		if (!source.isActive() || !destination.isActive()) {
			return accountNotActive(requestId);
		}
		// The decisive funds check uses the locked balance, never the early read.
		if (source.availableMinor() < amount) {
			return MutationResult.rejected(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_AVAILABLE_FUNDS",
					"Available funds do not cover this transfer.", requestId, json);
		}
		long newDestinationBalance;
		try {
			newDestinationBalance = Math.addExact(destination.creditBalanceMinor(), amount);
		}
		catch (ArithmeticException overflow) {
			return balanceLimitExceeded(requestId);
		}

		UUID journalId = UUID.randomUUID();
		OffsetDateTime createdAt = journalPoster.post(journalId, JournalKind.TRANSFER, amount, callerId, null,
				source.id(), destination.id());
		accounts.updateBalances(source.id(), source.creditBalanceMinor() - amount, source.reservedMinor());
		accounts.updateBalances(destination.id(), newDestinationBalance, destination.reservedMinor());
		events.record(callerId, "TRANSFER_POSTED", "JOURNAL", journalId, requestId,
				Map.of("sourceAccountId", source.id().toString(), "destinationAccountId",
						destination.id().toString(), "amountMinor", Money.format(amount)));
		return journalCreated(journalId, JournalKind.TRANSFER, amount, createdAt, requestId);
	}

	/** Any customer account may receive money; the control account is never a valid destination. */
	private AccountRow requireCustomerAccount(UUID accountId) {
		AccountRow account = accounts.findById(accountId).orElseThrow(ApiException::notFound);
		if (account.kind() != AccountKind.CUSTOMER) {
			throw ApiException.notFound();
		}
		return account;
	}

	private MutationResult journalCreated(UUID journalId, JournalKind kind, long amount, OffsetDateTime createdAt,
			String requestId) {
		return MutationResult.of(HttpStatus.CREATED, new JournalResponse(journalId.toString(), kind.name(),
				Money.format(amount), Money.CURRENCY, LedgerResponses.timestamp(createdAt), requestId), json);
	}

	private MutationResult accountNotActive(String requestId) {
		return MutationResult.rejected(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", "An account involved is not active.",
				requestId, json);
	}

	private MutationResult balanceLimitExceeded(String requestId) {
		return MutationResult.rejected(HttpStatus.UNPROCESSABLE_CONTENT, "BALANCE_LIMIT_EXCEEDED",
				"The resulting balance would exceed the supported range.", requestId, json);
	}

}
