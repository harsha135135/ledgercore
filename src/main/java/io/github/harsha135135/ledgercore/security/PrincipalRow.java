package io.github.harsha135135.ledgercore.security;

import java.util.UUID;

/** A principal with the role read from PostgreSQL for the current request. */
public record PrincipalRow(UUID id, Role role) {

	public boolean isAdmin() {
		return role == Role.ADMIN;
	}

}
