package io.github.harsha135135.ledgercore.hold;

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
import io.github.harsha135135.ledgercore.hold.HoldMessages.CreateHoldCommand;
import io.github.harsha135135.ledgercore.hold.HoldMessages.HoldResponse;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyRequest;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyService;
import io.github.harsha135135.ledgercore.ledger.AccountKind;
import io.github.harsha135135.ledgercore.ledger.AccountLocks;
import io.github.harsha135135.ledgercore.ledger.AccountRepository;
import io.github.harsha135135.ledgercore.ledger.AccountRow;
import io.github.harsha135135.ledgercore.ledger.JournalKind;
import io.github.harsha135135.ledgercore.ledger.JournalPoster;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalResponse;
import io.github.harsha135135.ledgercore.ledger.Money;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.github.harsha135135.ledgercore.security.PrincipalRepository;
import io.github.harsha135135.ledgercore.security.PrincipalRow;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Full holds: reserve available funds, then either capture the whole amount (a transfer to the
 * fixed destination) or release it. Capture and release take the same locks in the same order,
 * so when they race exactly one terminal transition succeeds.
 *
 * <p>Lock order for hold actions: both accounts through {@link AccountLocks}, then the hold row.
 * The hold's state is read only after its own lock is held.
 */
@Service
public class HoldService {

	private final PrincipalRepository principals;
	private final AccountRepository accounts;
	private final AccountLocks accountLocks;
	private final HoldRepository holds;
	private final JournalPoster journalPoster;
	private final BusinessEvents events;
	private final IdempotencyService idempotency;
	private final LedgerTransactions transactions;
	private final TransactionCheckpoints checkpoints;
	private final JsonText json;

	public HoldService(PrincipalRepository principals, AccountRepository accounts, AccountLocks accountLocks,
			HoldRepository holds, JournalPoster journalPoster, BusinessEvents events, IdempotencyService idempotency,
			LedgerTransactions transactions, TransactionCheckpoints checkpoints, JsonText json) {
		this.principals = principals;
		this.accounts = accounts;
		this.accountLocks = accountLocks;
		this.holds = holds;
		this.journalPoster = journalPoster;
		this.events = events;
		this.idempotency = idempotency;
		this.transactions = transactions;
		this.checkpoints = checkpoints;
		this.json = json;
	}

	/** The source owner reserves part of the source's available funds for a fixed destination. */
	public MutationResult createHold(UUID callerId, CreateHoldCommand command, String idempotencyKey,
			String requestId) {
		PrincipalRow caller = principals.requireCurrent(callerId);
		AccountRow source = accounts.findById(command.sourceAccountId()).orElseThrow(ApiException::notFound);
		if (!source.isCustomerOwnedBy(caller.id())) {
			throw ApiException.notFound();
		}
		AccountRow destination = accounts.findById(command.destinationAccountId()).orElseThrow(ApiException::notFound);
		if (destination.kind() != AccountKind.CUSTOMER) {
			throw ApiException.notFound();
		}
		long amount = command.amountMinor();
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "HOLD_CREATE", idempotencyKey,
				"sourceAccountId", source.id().toString(), "destinationAccountId", destination.id().toString(),
				"amountMinor", Money.format(amount), "currency", Money.CURRENCY);

		MutationResult result = transactions.inMutationTransaction(() -> {
			principals.requireCurrent(callerId);
			return idempotency.runOnce(keyRequest, () -> {
				Map<UUID, AccountRow> locked = accountLocks.lockInCanonicalOrder(List.of(source.id(), destination.id()));
				checkpoints.reached(Checkpoint.AFTER_ACCOUNT_LOCKS);
				AccountRow lockedSource = locked.get(source.id());
				AccountRow lockedDestination = locked.get(destination.id());
				if (!lockedSource.isCustomerOwnedBy(callerId)) {
					throw ApiException.notFound();
				}
				if (!lockedSource.isActive() || !lockedDestination.isActive()) {
					return rejected(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", "An account involved is not active.",
							requestId);
				}
				if (lockedSource.availableMinor() < amount) {
					return rejected(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_AVAILABLE_FUNDS",
							"Available funds do not cover this hold.", requestId);
				}
				// Cannot overflow: the new reservation stays at or below the posted balance.
				long newReserved = lockedSource.reservedMinor() + amount;

				HoldRow hold = holds.insertActive(UUID.randomUUID(), lockedSource.id(), lockedDestination.id(), amount);
				accounts.updateBalances(lockedSource.id(), lockedSource.creditBalanceMinor(), newReserved);
				events.record(callerId, "HOLD_CREATED", "HOLD", hold.id(), requestId,
						Map.of("sourceAccountId", lockedSource.id().toString(), "destinationAccountId",
								lockedDestination.id().toString(), "amountMinor", Money.format(amount)));
				return MutationResult.of(HttpStatus.CREATED, HoldResponse.from(hold, requestId), json);
			});
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	/** Transfers the whole reservation to the hold's destination and ends the hold. */
	public MutationResult captureHold(UUID callerId, UUID holdId, String idempotencyKey, String requestId) {
		HoldRow hold = authorizeHoldAction(callerId, holdId);
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "HOLD_CAPTURE:" + holdId, idempotencyKey,
				"holdId", holdId.toString());

		MutationResult result = transactions.inMutationTransaction(() -> {
			reauthorizeHoldAction(callerId, hold);
			return idempotency.runOnce(keyRequest, () -> {
				Map<UUID, AccountRow> locked = accountLocks
					.lockInCanonicalOrder(List.of(hold.sourceId(), hold.destinationId()));
				HoldRow lockedHold = holds.lockById(holdId);
				checkpoints.reached(Checkpoint.AFTER_ACCOUNT_LOCKS);
				if (lockedHold.state() != HoldState.ACTIVE) {
					return holdNotActive(requestId);
				}
				AccountRow source = locked.get(hold.sourceId());
				AccountRow destination = locked.get(hold.destinationId());
				if (!source.isActive() || !destination.isActive()) {
					return rejected(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", "An account involved is not active.",
							requestId);
				}
				long amount = lockedHold.amountMinor();
				long newDestinationBalance;
				try {
					newDestinationBalance = Math.addExact(destination.creditBalanceMinor(), amount);
				}
				catch (ArithmeticException overflow) {
					return rejected(HttpStatus.UNPROCESSABLE_CONTENT, "BALANCE_LIMIT_EXCEEDED",
							"The resulting balance would exceed the supported range.", requestId);
				}

				// The reservation already guaranteed these funds: posted >= reserved >= amount.
				UUID journalId = UUID.randomUUID();
				OffsetDateTime createdAt = journalPoster.post(journalId, JournalKind.CAPTURE, amount, callerId, null,
						source.id(), destination.id());
				accounts.updateBalances(source.id(), source.creditBalanceMinor() - amount,
						source.reservedMinor() - amount);
				accounts.updateBalances(destination.id(), newDestinationBalance, destination.reservedMinor());
				holds.markCaptured(holdId, journalId);
				events.record(callerId, "HOLD_CAPTURED", "HOLD", holdId, requestId,
						Map.of("captureJournalId", journalId.toString(), "amountMinor", Money.format(amount)));
				return MutationResult.of(HttpStatus.CREATED, new JournalResponse(journalId.toString(),
						JournalKind.CAPTURE.name(), Money.format(amount), Money.CURRENCY,
						LedgerResponses.timestamp(createdAt), requestId), json);
			});
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	/** Ends the hold without moving money; the reservation is returned to available funds. */
	public MutationResult releaseHold(UUID callerId, UUID holdId, String idempotencyKey, String requestId) {
		HoldRow hold = authorizeHoldAction(callerId, holdId);
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "HOLD_RELEASE:" + holdId, idempotencyKey,
				"holdId", holdId.toString());

		MutationResult result = transactions.inMutationTransaction(() -> {
			reauthorizeHoldAction(callerId, hold);
			return idempotency.runOnce(keyRequest, () -> {
				Map<UUID, AccountRow> locked = accountLocks
					.lockInCanonicalOrder(List.of(hold.sourceId(), hold.destinationId()));
				HoldRow lockedHold = holds.lockById(holdId);
				checkpoints.reached(Checkpoint.AFTER_ACCOUNT_LOCKS);
				if (lockedHold.state() != HoldState.ACTIVE) {
					return holdNotActive(requestId);
				}
				AccountRow source = locked.get(hold.sourceId());
				accounts.updateBalances(source.id(), source.creditBalanceMinor(),
						source.reservedMinor() - lockedHold.amountMinor());
				HoldRow released = holds.markReleased(holdId);
				events.record(callerId, "HOLD_RELEASED", "HOLD", holdId, requestId,
						Map.of("amountMinor", Money.format(lockedHold.amountMinor())));
				return MutationResult.of(HttpStatus.OK, HoldResponse.from(released, requestId), json);
			});
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	public HoldResponse getHold(UUID callerId, UUID holdId, String requestId) {
		return HoldResponse.from(authorizeHoldAction(callerId, holdId), requestId);
	}

	/**
	 * Only the source owner or an ADMIN may see or act on a hold. The recipient gets the same 404
	 * as for a missing hold. Participants are immutable, so these ordinary reads are safe.
	 */
	private HoldRow authorizeHoldAction(UUID callerId, UUID holdId) {
		PrincipalRow caller = principals.requireCurrent(callerId);
		HoldRow hold = holds.findById(holdId).orElseThrow(ApiException::notFound);
		AccountRow source = accounts.findById(hold.sourceId()).orElseThrow(ApiException::notFound);
		if (!caller.isAdmin() && !caller.id().equals(source.ownerId())) {
			throw ApiException.notFound();
		}
		return hold;
	}

	/** Inside the transaction, before the key claim: a caller who lost ADMIN since the early check is refused. */
	private void reauthorizeHoldAction(UUID callerId, HoldRow hold) {
		PrincipalRow current = principals.requireCurrent(callerId);
		AccountRow source = accounts.findById(hold.sourceId()).orElseThrow(ApiException::notFound);
		if (!current.isAdmin() && !current.id().equals(source.ownerId())) {
			throw ApiException.notFound();
		}
	}

	private MutationResult holdNotActive(String requestId) {
		return rejected(HttpStatus.CONFLICT, "HOLD_NOT_ACTIVE", "The hold has already been captured or released.",
				requestId);
	}

	private MutationResult rejected(HttpStatus status, String code, String message, String requestId) {
		return MutationResult.rejected(status, code, message, requestId, json);
	}

}
