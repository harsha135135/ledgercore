package io.github.harsha135135.ledgercore.ledger;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.hold.HoldMessages.CreateHoldCommand;
import io.github.harsha135135.ledgercore.hold.HoldService;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.support.ConcurrentCalls;
import io.github.harsha135135.ledgercore.support.ConcurrentCalls.Outcome;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static io.github.harsha135135.ledgercore.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReversalTest extends IntegrationTest {

	@Autowired
	private ReversalService reversalService;

	@Autowired
	private HoldService holdService;

	@Test
	void reversalPostsACompensatingJournalAndLeavesTheOriginalUntouched() {
		UUID payer = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID recipient = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID original = transfer(payer, recipient, 3_000);

		MutationResult reversal = reverse(original, TestFixtures.newKey());
		assertThat(reversal.httpStatus()).isEqualTo(201);
		assertThat(TestFixtures.field(reversal, "kind")).isEqualTo("REVERSAL");
		assertThat(postedBalance(payer)).isEqualTo(10_000);
		assertThat(postedBalance(recipient)).isZero();

		UUID reversalId = UUID.fromString(TestFixtures.field(reversal, "journalId"));
		assertThat(jdbc.queryForObject("SELECT reverses_id FROM journals WHERE id = ?", UUID.class, reversalId))
			.isEqualTo(original);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM postings WHERE journal_id = ?", Integer.class, original))
			.as("original postings are kept").isEqualTo(2);
		assertLedgerConsistent();
	}

	@Test
	void aJournalCanBeReversedOnlyOnceEvenUnderConcurrency() throws Exception {
		UUID payer = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID recipient = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID original = transfer(payer, recipient, 2_000);

		List<Outcome> outcomes = ConcurrentCalls.runTogether(10,
				i -> ConcurrentCalls.withTransientRetry(() -> reverse(original, TestFixtures.newKey()), 10),
				Duration.ofSeconds(30));
		long created = outcomes.stream().filter(o -> o.result().httpStatus() == 201).count();
		long alreadyReversed = outcomes.stream()
			.filter(o -> o.result().httpStatus() == 409 && o.result().bodyJson().contains("ALREADY_REVERSED"))
			.count();
		assertThat(created).isEqualTo(1);
		assertThat(alreadyReversed).isEqualTo(9);
		assertThat(postedBalance(payer)).isEqualTo(10_000);
		assertLedgerConsistent();
	}

	@Test
	void reversalNeedsTheRecipientsAvailableFundsIncludingItsHolds() {
		UUID payer = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID recipient = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID elsewhere = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);
		UUID original = transfer(payer, recipient, 3_000);
		// The recipient reserves 2,000 of the 3,000, leaving only 1,000 available.
		MutationResult hold = holdService.createHold(TestUsers.CUSTOMER_B_ID,
				new CreateHoldCommand(recipient, elsewhere, 2_000), TestFixtures.newKey(), TestFixtures.requestId());
		assertThat(hold.httpStatus()).isEqualTo(201);

		MutationResult reversal = reverse(original, TestFixtures.newKey());
		assertThat(reversal.httpStatus()).isEqualTo(422);
		assertThat(reversal.bodyJson()).contains("INSUFFICIENT_AVAILABLE_FUNDS");
		assertThat(postedBalance(recipient)).isEqualTo(3_000);
		assertThat(postedBalance(payer)).isEqualTo(7_000);
		assertLedgerConsistent();
	}

	@Test
	void reversingACaptureDoesNotReactivateTheHold() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID holdId = UUID.fromString(TestFixtures.field(holdService.createHold(TestUsers.CUSTOMER_A_ID,
				new CreateHoldCommand(source, destination, 4_000), TestFixtures.newKey(), "r"), "holdId"));
		UUID capture = UUID.fromString(TestFixtures
			.field(holdService.captureHold(TestUsers.CUSTOMER_A_ID, holdId, TestFixtures.newKey(), "r"), "journalId"));

		assertThat(reverse(capture, TestFixtures.newKey()).httpStatus()).isEqualTo(201);
		assertThat(postedBalance(source)).isEqualTo(10_000);
		assertThat(reservedBalance(source)).isZero();
		assertThat(holdService.getHold(TestUsers.CUSTOMER_A_ID, holdId, "r").state()).isEqualTo("CAPTURED");
		assertLedgerConsistent();
	}

	@Test
	void fundingAndReversalJournalsCannotBeReversed() {
		UUID account = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);
		UUID funding = UUID.fromString(fixtures.fund(account, 5_000));
		assertThatThrownBy(() -> reverse(funding, TestFixtures.newKey()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("JOURNAL_NOT_REVERSIBLE"));

		UUID recipient = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID original = transfer(account, recipient, 1_000);
		UUID reversal = UUID.fromString(TestFixtures.field(reverse(original, TestFixtures.newKey()), "journalId"));
		assertThatThrownBy(() -> reverse(reversal, TestFixtures.newKey()))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("JOURNAL_NOT_REVERSIBLE"));
	}

	@Test
	void onlyAdministratorsCanReverseAndAReasonIsRequired() {
		UUID payer = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID recipient = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		UUID original = transfer(payer, recipient, 1_000);

		var byCustomer = api().post("/v1/journals/" + original + "/reversal", TestUsers.CUSTOMER_A,
				json("reason", "customer asks for money back"));
		assertThat(byCustomer.status()).isEqualTo(403);
		var withoutReason = api().post("/v1/journals/" + original + "/reversal", TestUsers.ADMIN, json("reason", " "));
		assertThat(withoutReason.status()).isEqualTo(400);
		assertThat(withoutReason.text("code")).isEqualTo("INVALID_REASON");
		var byAdmin = api().post("/v1/journals/" + original + "/reversal", TestUsers.ADMIN,
				json("reason", "duplicate payment"));
		assertThat(byAdmin.status()).isEqualTo(201);
		assertThat(postedBalance(payer)).isEqualTo(10_000);
	}

	private UUID transfer(UUID source, UUID destination, long amount) {
		MutationResult result = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
				new TransferCommand(source, destination, amount), TestFixtures.newKey(), TestFixtures.requestId());
		assertThat(result.httpStatus()).isEqualTo(201);
		return UUID.fromString(TestFixtures.field(result, "journalId"));
	}

	private MutationResult reverse(UUID journalId, String key) {
		return reversalService.reverse(TestUsers.ADMIN_ID, journalId, "test reversal", key, TestFixtures.requestId());
	}

}
