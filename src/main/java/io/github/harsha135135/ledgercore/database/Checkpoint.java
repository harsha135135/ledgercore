package io.github.harsha135135.ledgercore.database;

/** Named points in a mutation where tests may pause a transaction or inject a failure. */
public enum Checkpoint {

	/** Inside the transaction, after the idempotency key row has been inserted but not committed. */
	AFTER_IDEMPOTENCY_CLAIM,

	/** Inside the transaction, after every affected account row is locked. */
	AFTER_ACCOUNT_LOCKS,

	/** Inside the transaction, after all writes and immediately before commit. */
	BEFORE_COMMIT,

	/** After commit, before the response is returned: a failure here models a lost response. */
	AFTER_COMMIT

}
