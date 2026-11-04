package io.github.harsha135135.ledgercore.database;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two transaction shapes used by the ledger, named so a reader can see the boundary in each
 * service method.
 */
@Component
public class LedgerTransactions {

	/** Matches the 10-second server-side transaction_timeout configured on the pool. */
	private static final int MUTATION_TIMEOUT_SECONDS = 10;
	private static final int SNAPSHOT_TIMEOUT_SECONDS = 60;

	private final TransactionTemplate mutation;
	private final TransactionTemplate readOnlySnapshot;

	public LedgerTransactions(PlatformTransactionManager transactionManager) {
		// READ COMMITTED plus explicit row locks. After losing an idempotency-key race, a new
		// statement can see the winner's committed row; REPEATABLE READ would keep the old snapshot.
		this.mutation = new TransactionTemplate(transactionManager);
		this.mutation.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.mutation.setTimeout(MUTATION_TIMEOUT_SECONDS);

		// Reconciliation runs several queries that must all see the same committed moment.
		this.readOnlySnapshot = new TransactionTemplate(transactionManager);
		this.readOnlySnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.readOnlySnapshot.setReadOnly(true);
		this.readOnlySnapshot.setTimeout(SNAPSHOT_TIMEOUT_SECONDS);
	}

	/** Runs one financial mutation. An exception rolls back every write made inside it. */
	public <T> T inMutationTransaction(Supplier<T> work) {
		return mutation.execute(status -> work.get());
	}

	/** Runs read-only work against one REPEATABLE READ snapshot. */
	public <T> T inReadOnlySnapshot(Supplier<T> work) {
		return readOnlySnapshot.execute(status -> work.get());
	}

}
