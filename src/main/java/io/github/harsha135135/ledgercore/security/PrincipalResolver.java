package io.github.harsha135135.ledgercore.security;

import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.config.LedgerProperties;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Maps an authenticated username to its ledger principal: username, then configured subject,
 * then the principal row. Any missing link fails closed. Roles are never taken from the request.
 */
@Component
public class PrincipalResolver {

	private final LedgerProperties properties;
	private final PrincipalRepository principals;

	public PrincipalResolver(LedgerProperties properties, PrincipalRepository principals) {
		this.properties = properties;
		this.principals = principals;
	}

	public UUID principalIdFor(Authentication authentication) {
		String subject = properties.security()
			.subjectForUsername(authentication.getName())
			.orElseThrow(PrincipalResolver::notProvisioned);
		return principals.findIdBySubject(subject).orElseThrow(PrincipalResolver::notProvisioned);
	}

	private static ApiException notProvisioned() {
		return new ApiException(HttpStatus.FORBIDDEN, "PRINCIPAL_NOT_PROVISIONED",
				"Your login is not mapped to a ledger principal.");
	}

}
