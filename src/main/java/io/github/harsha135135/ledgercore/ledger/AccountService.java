package io.github.harsha135135.ledgercore.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.api.JsonText;
import io.github.harsha135135.ledgercore.outbox.BusinessEvents;
import io.github.harsha135135.ledgercore.config.LedgerProperties;
import io.github.harsha135135.ledgercore.database.Checkpoint;
import io.github.harsha135135.ledgercore.database.LedgerTransactions;
import io.github.harsha135135.ledgercore.database.TransactionCheckpoints;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyRequest;
import io.github.harsha135135.ledgercore.idempotency.IdempotencyService;
import io.github.harsha135135.ledgercore.ledger.AccountRepository.BalanceRow;
import io.github.harsha135135.ledgercore.ledger.JournalRepository.AccountJournalEntry;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.AccountResponse;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.BalanceResponse;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalPageItem;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalPageResponse;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.NewAccountResponse;
import io.github.harsha135135.ledgercore.security.Permissions;
import io.github.harsha135135.ledgercore.security.PrincipalRepository;
import io.github.harsha135135.ledgercore.security.PrincipalRow;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Account creation and the account read endpoints. */
@Service
public class AccountService {

	private final PrincipalRepository principals;
	private final AccountRepository accounts;
	private final JournalRepository journals;
	private final BusinessEvents events;
	private final IdempotencyService idempotency;
	private final LedgerTransactions transactions;
	private final TransactionCheckpoints checkpoints;
	private final JsonText json;
	private final LedgerProperties.Limits limits;

	public AccountService(PrincipalRepository principals, AccountRepository accounts, JournalRepository journals,
			BusinessEvents events, IdempotencyService idempotency, LedgerTransactions transactions,
			TransactionCheckpoints checkpoints, JsonText json, LedgerProperties properties) {
		this.principals = principals;
		this.accounts = accounts;
		this.journals = journals;
		this.events = events;
		this.idempotency = idempotency;
		this.transactions = transactions;
		this.checkpoints = checkpoints;
		this.json = json;
		this.limits = properties.limits();
	}

	/** ADMIN creates an ACTIVE customer account with zero balances and an immutable owner. */
	public MutationResult createAccount(UUID callerId, UUID ownerId, String idempotencyKey, String requestId) {
		Permissions.requireAdmin(principals.requireCurrent(callerId));
		if (principals.findById(ownerId).isEmpty()) {
			throw ApiException.notFound();
		}
		IdempotencyRequest keyRequest = IdempotencyRequest.of(callerId, "ACCOUNT_CREATE", idempotencyKey,
				"ownerId", ownerId.toString());

		MutationResult result = transactions.inMutationTransaction(() -> {
			// Re-read the role inside the transaction: a demotion committed since the early check wins.
			Permissions.requireAdmin(principals.requireCurrent(callerId));
			return idempotency.runOnce(keyRequest, () -> {
				UUID accountId = UUID.randomUUID();
				AccountRow account = accounts.insertCustomerAccount(accountId, ownerId);
				events.record(callerId, "ACCOUNT_CREATED", "ACCOUNT", accountId, requestId,
						Map.of("ownerId", ownerId.toString()));
				return MutationResult.of(HttpStatus.CREATED, NewAccountResponse.from(account, requestId), json);
			});
		});
		checkpoints.reached(Checkpoint.AFTER_COMMIT);
		return result;
	}

	public AccountResponse getAccount(UUID callerId, UUID accountId, String requestId) {
		PrincipalRow caller = principals.requireCurrent(callerId);
		AccountRow account = accounts.findById(accountId).orElseThrow(ApiException::notFound);
		if (!Permissions.canInspect(caller, account.ownerId())) {
			throw ApiException.notFound();
		}
		return AccountResponse.from(account, requestId);
	}

	/** Posted, reserved and available funds, all from one statement so they agree with each other. */
	public BalanceResponse getBalance(UUID callerId, UUID accountId, String requestId) {
		PrincipalRow caller = principals.requireCurrent(callerId);
		BalanceRow balance = accounts.readBalance(accountId).orElseThrow(ApiException::notFound);
		if (!Permissions.canInspect(caller, balance.ownerId())) {
			throw ApiException.notFound();
		}
		return new BalanceResponse(accountId.toString(), Money.CURRENCY, Money.format(balance.creditBalanceMinor()),
				Money.format(balance.reservedMinor()),
				Money.format(balance.creditBalanceMinor() - balance.reservedMinor()),
				LedgerResponses.timestamp(balance.observedAt()), requestId);
	}

	/** The caller's side of each journal touching the account, oldest first. */
	public JournalPageResponse listJournals(UUID callerId, UUID accountId, String cursor, String limitText,
			String requestId) {
		PrincipalRow caller = principals.requireCurrent(callerId);
		AccountRow account = accounts.findById(accountId).orElseThrow(ApiException::notFound);
		if (!Permissions.canInspect(caller, account.ownerId())) {
			throw ApiException.notFound();
		}
		int pageSize = parsePageSize(limitText);
		JournalCursor after = cursor == null || cursor.isEmpty() ? null : JournalCursor.decode(cursor);

		// Fetch one extra row to learn whether another page follows.
		List<AccountJournalEntry> rows = journals.findPageForAccount(accountId, after, pageSize + 1);
		boolean hasMore = rows.size() > pageSize;
		List<JournalPageItem> items = new ArrayList<>();
		for (int i = 0; i < Math.min(rows.size(), pageSize); i++) {
			AccountJournalEntry row = rows.get(i);
			items.add(new JournalPageItem(row.journalId().toString(), row.kind().name(), row.side().name(),
					Money.format(row.amountMinor()), LedgerResponses.timestamp(row.createdAt())));
		}
		String nextCursor = null;
		if (hasMore) {
			AccountJournalEntry last = rows.get(pageSize - 1);
			nextCursor = new JournalCursor(last.createdAt(), last.journalId()).encode();
		}
		return new JournalPageResponse(accountId.toString(), items, nextCursor, requestId);
	}

	private int parsePageSize(String limitText) {
		if (limitText == null || limitText.isEmpty()) {
			return limits.defaultPageSize();
		}
		if (!limitText.matches("^[1-9][0-9]{0,3}$") || Integer.parseInt(limitText) > limits.maxPageSize()) {
			throw ApiException.badRequest("INVALID_LIMIT",
					"limit must be a whole number from 1 to " + limits.maxPageSize() + ".");
		}
		return Integer.parseInt(limitText);
	}

}
