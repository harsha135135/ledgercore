package io.github.harsha135135.ledgercore.ledger;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness fails unless exactly one ACTIVE SYSTEM_CONTROL account exists, because funding
 * cannot be posted without it. Exposed as the "controlAccount" readiness component.
 */
@Component
public class ControlAccountHealthIndicator implements HealthIndicator {

	private final AccountRepository accounts;

	public ControlAccountHealthIndicator(AccountRepository accounts) {
		this.accounts = accounts;
	}

	@Override
	public Health health() {
		int activeControlAccounts = accounts.countActiveSystemControlAccounts();
		if (activeControlAccounts == 1) {
			return Health.up().build();
		}
		return Health.down().withDetail("activeControlAccounts", activeControlAccounts).build();
	}

}
