package io.github.harsha135135.ledgercore.hold;

/** ACTIVE becomes exactly one of the terminal states, CAPTURED or RELEASED, and never changes again. */
public enum HoldState {
	ACTIVE, CAPTURED, RELEASED
}
