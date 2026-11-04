package io.github.harsha135135.ledgercore.database;

/**
 * Called by the services at each {@link Checkpoint}. Production uses
 * {@link NoTransactionCheckpoints}, which does nothing. Only the test suite supplies an
 * implementation that pauses transactions or throws, so race and crash tests can prove
 * an interleaving actually happened.
 */
public interface TransactionCheckpoints {

	void reached(Checkpoint checkpoint);

}
