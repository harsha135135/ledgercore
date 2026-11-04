package io.github.harsha135135.ledgercore.ledger;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One account as stored. {@code creditBalanceMinor} is credits minus debits; for a customer,
 * available funds are that balance minus active reservations.
 */
public record AccountRow(
		UUID id,
		UUID ownerId,
		AccountKind kind,
		String currency,
		AccountState state,
		long creditBalanceMinor,
		long reservedMinor,
		OffsetDateTime createdAt) {

	public long availableMinor() {
		return creditBalanceMinor - reservedMinor;
	}

	public boolean isActive() {
		return state == AccountState.ACTIVE;
	}

	public boolean isCustomerOwnedBy(UUID principalId) {
		return kind == AccountKind.CUSTOMER && principalId.equals(ownerId);
	}

}
