package io.github.harsha135135.ledgercore.ledger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.BalanceResponse;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalPageItem;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses.JournalPageResponse;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Journal shape and balance projection for funding and transfers, at the service boundary. */
class LedgerFlowTest extends IntegrationTest {

	@Test
	void fundingDebitsControlAndCreditsTheCustomer() {
		UUID controlId = jdbc.queryForObject("SELECT id FROM accounts WHERE kind = 'SYSTEM_CONTROL'", UUID.class);
		long controlBefore = postedBalance(controlId);
		UUID account = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);

		String journalId = fixtures.fund(account, 10_000);

		assertThat(postedBalance(account)).isEqualTo(10_000);
		assertThat(postedBalance(controlId)).isEqualTo(controlBefore - 10_000);
		List<Map<String, Object>> postings = jdbc.queryForList(
				"SELECT line_no, account_id, side, amount_minor FROM postings WHERE journal_id = ? ORDER BY line_no",
				UUID.fromString(journalId));
		assertThat(postings).hasSize(2);
		assertThat(postings.get(0)).containsEntry("side", "DEBIT").containsEntry("account_id", controlId)
			.containsEntry("amount_minor", 10_000L);
		assertThat(postings.get(1)).containsEntry("side", "CREDIT").containsEntry("account_id", account)
			.containsEntry("amount_minor", 10_000L);
		assertLedgerConsistent();
	}

	@Test
	void transferMovesMoneyWithOneBalancedJournalAndAnAuditEvent() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);

		MutationResult result = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
				new TransferCommand(source, destination, 3_000), TestFixtures.newKey(), TestFixtures.requestId());

		assertThat(result.httpStatus()).isEqualTo(201);
		UUID journalId = UUID.fromString(TestFixtures.field(result, "journalId"));
		assertThat(postedBalance(source)).isEqualTo(7_000);
		assertThat(postedBalance(destination)).isEqualTo(3_000);
		assertThat(jdbc.queryForObject("SELECT kind FROM journals WHERE id = ?", String.class, journalId))
			.isEqualTo("TRANSFER");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM audit_events WHERE resource_id = ? AND action = 'TRANSFER_POSTED'", Integer.class,
				journalId)).isEqualTo(1);
		assertLedgerConsistent();
	}

	@Test
	void insufficientFundsChangesNothing() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 2_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);

		MutationResult result = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
				new TransferCommand(source, destination, 2_001), TestFixtures.newKey(), TestFixtures.requestId());

		assertThat(result.httpStatus()).isEqualTo(422);
		assertThat(result.bodyJson()).contains("INSUFFICIENT_AVAILABLE_FUNDS");
		assertThat(postedBalance(source)).isEqualTo(2_000);
		assertThat(postedBalance(destination)).isZero();
		assertThat(journalCountFor(destination)).isZero();
	}

	@Test
	void transferOfTheWholeBalanceLeavesZero() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 2_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		MutationResult result = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
				new TransferCommand(source, destination, 2_000), TestFixtures.newKey(), TestFixtures.requestId());
		assertThat(result.httpStatus()).isEqualTo(201);
		assertThat(postedBalance(source)).isZero();
	}

	@Test
	void journalPagesShowTheAccountsOwnSideInOrderWithCursors() {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 9_000);
		UUID other = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		for (int i = 0; i < 3; i++) {
			ledgerService.transfer(TestUsers.CUSTOMER_A_ID, new TransferCommand(account, other, 1_000), TestFixtures.newKey(),
					TestFixtures.requestId());
		}

		JournalPageResponse first = accountService.listJournals(TestUsers.CUSTOMER_A_ID, account, null, "2", "r1");
		assertThat(first.items()).extracting(JournalPageItem::kind).containsExactly("FUNDING", "TRANSFER");
		assertThat(first.items()).extracting(JournalPageItem::side).containsExactly("CREDIT", "DEBIT");
		assertThat(first.nextCursor()).isNotNull();

		JournalPageResponse second = accountService.listJournals(TestUsers.CUSTOMER_A_ID, account,
				first.nextCursor(), "2", "r2");
		assertThat(second.items()).extracting(JournalPageItem::kind).containsExactly("TRANSFER", "TRANSFER");
		assertThat(second.nextCursor()).isNull();

		BalanceResponse balance = accountService.getBalance(TestUsers.CUSTOMER_A_ID, account, "r3");
		assertThat(balance.postedMinor()).isEqualTo("6000");
		assertThat(balance.reservedMinor()).isEqualTo("0");
		assertThat(balance.availableMinor()).isEqualTo("6000");
	}

	@Test
	void reconciliationIsConsistentAndTheGlobalTotalIsZero() {
		fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_234);
		assertLedgerConsistent();
		assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(credit_balance_minor), 0) FROM accounts", Long.class))
			.isZero();
	}

}
