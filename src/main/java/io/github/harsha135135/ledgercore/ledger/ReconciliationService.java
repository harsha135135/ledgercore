package io.github.harsha135135.ledgercore.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.harsha135135.ledgercore.ledger.ReconciliationQueries.ProjectionMismatch;
import io.github.harsha135135.ledgercore.database.LedgerTransactions;
import io.github.harsha135135.ledgercore.security.Permissions;
import io.github.harsha135135.ledgercore.security.PrincipalRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Service;

/**
 * Checks the balance projection against journal history in one read-only REPEATABLE READ
 * snapshot, so every check sees the same committed moment. Discrepancies are reported, never
 * repaired automatically.
 *
 * <p>The zero total across all accounts is necessary but not sufficient: a wrong debit and a
 * wrong credit could cancel out, so each account is also rebuilt from its own postings.
 */
@Service
public class ReconciliationService {

	private final ReconciliationQueries queries;
	private final PrincipalRepository principals;
	private final LedgerTransactions transactions;
	private final Counter runs;
	private final Counter failures;

	public ReconciliationService(ReconciliationQueries queries, PrincipalRepository principals,
			LedgerTransactions transactions, MeterRegistry meters) {
		this.queries = queries;
		this.principals = principals;
		this.transactions = transactions;
		this.runs = Counter.builder("ledgercore.reconciliation.runs").register(meters);
		this.failures = Counter.builder("ledgercore.reconciliation.failures")
			.description("Reconciliation runs that found at least one discrepancy")
			.register(meters);
	}

	public ReconciliationReport reconcileAs(UUID callerId, String requestId) {
		Permissions.requireAdmin(principals.requireCurrent(callerId));
		List<String> problems = reconcile();
		runs.increment();
		if (!problems.isEmpty()) {
			failures.increment();
		}
		return new ReconciliationReport(problems.isEmpty(), problems, Instant.now().toString(), requestId);
	}

	/** Runs every check and returns human-readable problems; an empty list means consistent. */
	public List<String> reconcile() {
		return transactions.inReadOnlySnapshot(() -> {
			List<String> problems = new ArrayList<>();
			for (UUID journalId : queries.unbalancedJournals()) {
				problems.add("Journal " + journalId + " is not one debit and one equal credit on two accounts.");
			}
			for (ProjectionMismatch mismatch : queries.projectionMismatches()) {
				problems.add("Account " + mismatch.accountId() + " stores " + mismatch.storedMinor()
						+ " but its postings total " + mismatch.rebuiltMinor() + ".");
			}
			BigDecimal total = queries.totalOfAllBalances();
			if (total.signum() != 0) {
				problems.add("Balances across all accounts total " + total + " instead of 0.");
			}
			for (UUID accountId : queries.customersWithNegativeAvailability()) {
				problems.add("Customer account " + accountId + " has negative available funds.");
			}
			int controlAccounts = queries.systemControlAccountCount();
			if (controlAccounts != 1) {
				problems.add("Expected exactly one SYSTEM_CONTROL account but found " + controlAccounts + ".");
			}
			for (UUID accountId : queries.systemControlAccountsWithReservations()) {
				problems.add("SYSTEM_CONTROL account " + accountId + " has reservations.");
			}
			for (ProjectionMismatch mismatch : queries.reservationMismatches()) {
				problems.add("Account " + mismatch.accountId() + " reserves " + mismatch.storedMinor()
						+ " but its active holds total " + mismatch.rebuiltMinor() + ".");
			}
			for (UUID holdId : queries.capturedHoldsWithoutMatchingJournal()) {
				problems.add("Captured hold " + holdId + " does not link to a matching capture journal.");
			}
			for (UUID journalId : queries.captureJournalsWithoutHold()) {
				problems.add("Capture journal " + journalId + " is not linked to any hold.");
			}
			return problems;
		});
	}

	public record ReconciliationReport(boolean consistent, List<String> problems, String checkedAt,
			String requestId) {
	}

}
