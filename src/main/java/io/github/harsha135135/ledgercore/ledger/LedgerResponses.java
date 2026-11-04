package io.github.harsha135135.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.List;

/** JSON response bodies. Money and identifiers are strings; timestamps are UTC ISO-8601. */
public final class LedgerResponses {

	private LedgerResponses() {
	}

	public record JournalResponse(String journalId, String kind, String amountMinor, String currency,
			String createdAt, String requestId) {
	}

	public record AccountResponse(String accountId, String ownerId, String kind, String currency, String state,
			String createdAt, String requestId) {

		static AccountResponse from(AccountRow account, String requestId) {
			return new AccountResponse(account.id().toString(),
					account.ownerId() == null ? null : account.ownerId().toString(), account.kind().name(),
					account.currency(), account.state().name(), timestamp(account.createdAt()), requestId);
		}
	}

	/** Returned by account creation: the new account, with its zero balances. */
	public record NewAccountResponse(String accountId, String ownerId, String kind, String currency, String state,
			String postedMinor, String reservedMinor, String availableMinor, String createdAt, String requestId) {

		static NewAccountResponse from(AccountRow account, String requestId) {
			return new NewAccountResponse(account.id().toString(), account.ownerId().toString(),
					account.kind().name(), account.currency(), account.state().name(),
					Money.format(account.creditBalanceMinor()), Money.format(account.reservedMinor()),
					Money.format(account.availableMinor()), timestamp(account.createdAt()), requestId);
		}
	}

	public record BalanceResponse(String accountId, String currency, String postedMinor, String reservedMinor,
			String availableMinor, String observedAt, String requestId) {
	}

	public record JournalPageItem(String journalId, String kind, String side, String amountMinor, String createdAt) {
	}

	public record JournalPageResponse(String accountId, List<JournalPageItem> items, String nextCursor,
			String requestId) {
	}

	public static String timestamp(OffsetDateTime value) {
		return value == null ? null : value.toInstant().toString();
	}

}
