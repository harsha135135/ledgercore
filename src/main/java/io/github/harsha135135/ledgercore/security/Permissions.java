package io.github.harsha135135.ledgercore.security;

import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;

/** Authorization rules shared by the services. Ownership is immutable in this release. */
public final class Permissions {

	private Permissions() {
	}

	/** Funding, account creation, reversal, reconciliation and outbox recovery are ADMIN-only (403 otherwise). */
	public static void requireAdmin(PrincipalRow caller) {
		if (!caller.isAdmin()) {
			throw ApiException.forbidden();
		}
	}

	/** Owners and administrators may read an account; everyone else gets the same 404 as a missing account. */
	public static boolean canInspect(PrincipalRow caller, UUID ownerId) {
		return caller.isAdmin() || caller.id().equals(ownerId);
	}

}
