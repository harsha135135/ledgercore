package io.github.harsha135135.ledgercore.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The canonical lock order, and why {@code UUID.compareTo} must not be used for it. */
class AccountLocksTest {

	private static final UUID LOW = UUID.fromString("0fffffff-ffff-4fff-bfff-ffffffffffff");
	private static final UUID EIGHT = UUID.fromString("80000000-0000-4000-8000-000000000000");
	private static final UUID HIGH = UUID.fromString("f0000000-0000-4000-8000-000000000000");

	@Test
	void ordersByCanonicalLowercaseText() {
		assertThat(AccountLocks.canonicalLockOrder(List.of(HIGH, LOW, EIGHT))).containsExactly(LOW, EIGHT, HIGH);
	}

	@Test
	void signedUuidComparisonDisagreesAcrossTheHex8Boundary() {
		List<UUID> signedOrder = new ArrayList<>(List.of(HIGH, LOW, EIGHT));
		signedOrder.sort(UUID::compareTo);
		// UUID.compareTo treats a leading 8-f as a negative number, so those IDs sort first.
		assertThat(signedOrder).containsExactly(EIGHT, HIGH, LOW);
		assertThat(signedOrder).isNotEqualTo(AccountLocks.canonicalLockOrder(List.of(HIGH, LOW, EIGHT)));
	}

	@Test
	void equalFirstHalvesAreOrderedByTheSecondHalfText() {
		UUID secondHalfLow = UUID.fromString("12345678-9abc-4def-0000-000000000000");
		UUID secondHalfHigh = UUID.fromString("12345678-9abc-4def-f000-000000000000");
		assertThat(AccountLocks.canonicalLockOrder(List.of(secondHalfHigh, secondHalfLow)))
			.containsExactly(secondHalfLow, secondHalfHigh);
		// The signed comparison of the second half disagrees here too.
		assertThat(secondHalfHigh.compareTo(secondHalfLow)).isNegative();
	}

	@Test
	void sameOrderRegardlessOfDirectionAndDuplicatesRemoved() {
		assertThat(AccountLocks.canonicalLockOrder(List.of(LOW, HIGH)))
			.isEqualTo(AccountLocks.canonicalLockOrder(List.of(HIGH, LOW)));
		assertThat(AccountLocks.canonicalLockOrder(List.of(HIGH, HIGH, LOW))).containsExactly(LOW, HIGH);
	}

}
