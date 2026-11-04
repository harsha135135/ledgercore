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
import io.github.harsha135135.ledgercore.ledger.JournalRepository.JournalRow;
import io.github.harsha135135.ledgercore.ledger.JournalRepository.PostingRow;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalResponse;
import io.github.harsha135135.ledgercore.security.Permissions;
import io.github.harsha135135.ledgercore.security.PrincipalRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ADMIN-only compensation: a new REVERSAL journal with the original posting directions swapped.
 * The original entries are never edited. Only transfers and captures can be reversed, each at
 * most once, and only if the original recipient still has the funds available (its own holds
 * stay protected). Reversing a capture does not reactivate the hold.
 */
@Service
public class ReversalService {

	private final PrincipalRepository principals;
	private final AccountLocks accountLocks;
	private final JournalRepository journals;
	private final JournalPoster journalPoster;
	private final AccountRepository accounts;
	private final BusinessEvents events;
	private final IdempotencyService idempotency;
	private final LedgerTransactions transactions;
	private final TransactionCheckpoints checkpoints;
	private final JsonText json;

	public ReversalService(PrincipalRepository principals, AccountLocks accountLocks, JournalRepository journals,
			JournalPoster journalPoster, AccountRepository accounts, BusinessEvents events,
			IdempotencyService idempotency, LedgerTransactions transactions, TransactionCheckpoints checkpoints,
			JsonText json) {
		this.principals = principals;
		this.accountLocks = accountLocks;
		this.journals = journals;
		this.journalPoster = journalPoster;
		this.accounts = accounts;
		this.events = events;
		this.idempotency = idempotency;
		this.transactions = transactions;
		this.checkpoints = checkpoints;
		this.json = json;
	}

	public MutationResult reverse(UUID callerId, UUID originalJournalId, String reason, String idempotencyKey,
			String requestId) {
		Permissions.requireAdmin(principals.requireCurrent(callerId));
		JournalRow original = journals.findById(originalJournalId).orElseThrow(ApiException::notFound);
		if (original.kind() != JournalKind.TRANSFER && original.kind() != JournalKind.CAPTURE) {
			throw ApiException.unprocessable("JOURNAL_NOT_REVERSIBLE",
					"Only transfer and capture journals can be reversed.");
		}
		// Original postings are immutable: line 1 debited the payer, line 2 credited the recipient.
		UUID originalPayer = null;
		UUID originalRecipient = null;
		for (PostingRow posting : journals.findPostings(originalJournalId)) {
			if (posting.side() == PostingSide.DEBIT) {
				originalPayer = posting.accountId();
			}
			else {
				originalRecipient = posting.accountId();
			}
		}
		if (originalPayer == null || originalRecipient == null) {
			throw new IllegalStateException("Journal " + originalJournalId + " does not have two postings.");
		}
		UUID payer = originalPayer;
		UUID recipient = originalRecipient;
		long amount = original.amountMinor();
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "REVERSAL:" + originalJournalId,
				idempotencyKey, "journalId", originalJournalId.toString(), "reason", reason);

		MutationResult result = transactions.inMutationTransaction(() -> {
			Permissions.requireAdmin(principals.requireCurrent(callerId));
			return idempotency.runOnce(keyRequest, () -> {
				Map<UUID, AccountRow> locked = accountLocks.lockInCanonicalOrder(List.of(payer, recipient));
				checkpoints.reached(Checkpoint.AFTER_ACCOUNT_LOCKS);
				// The account locks serialize competing reversals; the unique reverses_id is a backstop.
				if (journals.findReversalOf(originalJournalId).isPresent()) {
					return rejected(HttpStatus.CONFLICT, "ALREADY_REVERSED", "This journal has already been reversed.",
							requestId);
				}
				AccountRow lockedRecipient = locked.get(recipient);
				AccountRow lockedPayer = locked.get(payer);
				if (!lockedRecipient.isActive() || !lockedPayer.isActive()) {
					return rejected(HttpStatus.CONFLICT, "ACCOUNT_NOT_ACTIVE", "An account involved is not active.",
							requestId);
				}
				if (lockedRecipient.availableMinor() < amount) {
					return rejected(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_AVAILABLE_FUNDS",
							"The original recipient's available funds do not cover the reversal.", requestId);
				}
				long newPayerBalance;
				try {
					newPayerBalance = Math.addExact(lockedPayer.creditBalanceMinor(), amount);
				}
				catch (ArithmeticException overflow) {
					return rejected(HttpStatus.UNPROCESSABLE_CONTENT, "BALANCE_LIMIT_EXCEEDED",
							"The resulting balance would exceed the supported range.", requestId);
				}

				UUID reversalId = UUID.randomUUID();
				OffsetDateTime createdAt = journalPoster.post(reversalId, JournalKind.REVERSAL, amount, callerId,
						originalJournalId, recipient, payer);
				accounts.updateBalances(recipient, lockedRecipient.creditBalanceMinor() - amount,
						lockedRecipient.reservedMinor());
				accounts.updateBalances(payer, newPayerBalance, lockedPayer.reservedMinor());
				events.record(callerId, "JOURNAL_REVERSED", "JOURNAL", reversalId, requestId,
						Map.of("originalJournalId", originalJournalId.toString(), "amountMinor", Money.format(amount),
								"reason", reason));
				return MutationResult.of(HttpStatus.CREATED, new JournalResponse(reversalId.toString(),
						JournalKind.REVERSAL.name(), Money.format(amount), Money.CURRENCY,
						LedgerResponses.timestamp(createdAt), requestId), json);
			});
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	private MutationResult rejected(HttpStatus status, String code, String message, String requestId) {
		return MutationResult.rejected(status, code, message, requestId, json);
	}

}
