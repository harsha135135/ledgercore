package io.github.harsha135135.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Writes a balanced journal: exactly two postings of the journal amount, line 1 a DEBIT and
 * line 2 a CREDIT, on two different accounts. The database cannot enforce this cross-row rule,
 * so every money movement goes through here and reconciliation checks it afterwards.
 */
@Component
public class JournalPoster {

	private final JournalRepository journals;

	public JournalPoster(JournalRepository journals) {
		this.journals = journals;
	}

	public OffsetDateTime post(UUID journalId, JournalKind kind, long amountMinor, UUID actorId,
			UUID reversesJournalId, UUID debitAccountId, UUID creditAccountId) {
		if (debitAccountId.equals(creditAccountId)) {
			throw new IllegalArgumentException("A journal must move money between two different accounts.");
		}
		OffsetDateTime createdAt = journals.insertJournal(journalId, kind, amountMinor, actorId, reversesJournalId);
		journals.insertPosting(journalId, 1, debitAccountId, PostingSide.DEBIT, amountMinor);
		journals.insertPosting(journalId, 2, creditAccountId, PostingSide.CREDIT, amountMinor);
		return createdAt;
	}

}
