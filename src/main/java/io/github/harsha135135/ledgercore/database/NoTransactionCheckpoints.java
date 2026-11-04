package io.github.harsha135135.ledgercore.database;

import org.springframework.stereotype.Component;

/** The production implementation: checkpoints never pause or fail. */
@Component
public class NoTransactionCheckpoints implements TransactionCheckpoints {

	@Override
	public void reached(Checkpoint checkpoint) {
		// Intentionally empty.
	}

}
