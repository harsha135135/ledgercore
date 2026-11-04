package io.github.harsha135135.ledgercore.idempotency;

import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyUnitTest {

	private static final UUID PRINCIPAL = UUID.randomUUID();

	@Test
	void sameSemanticFieldsGiveTheSameHash() {
		IdempotencyRequest first = IdempotencyRequest.of(PRINCIPAL, "TRANSFER", "k", "amountMinor", "100");
		IdempotencyRequest retry = IdempotencyRequest.of(PRINCIPAL, "TRANSFER", "k", "amountMinor", "100");
		assertThat(first.requestHash()).isEqualTo(retry.requestHash()).hasSize(64);
	}

	@Test
	void anyChangedFieldOrScopeChangesTheHash() {
		String base = IdempotencyRequest.of(PRINCIPAL, "TRANSFER", "k", "amountMinor", "100").requestHash();
		assertThat(IdempotencyRequest.of(PRINCIPAL, "TRANSFER", "k", "amountMinor", "101").requestHash())
			.isNotEqualTo(base);
		assertThat(IdempotencyRequest.of(PRINCIPAL, "DEMO_FUNDING", "k", "amountMinor", "100").requestHash())
			.isNotEqualTo(base);
		// Field boundaries are explicit, so values cannot run together.
		assertThat(IdempotencyRequest.of(PRINCIPAL, "S", "k", "a", "1", "b", "2").requestHash())
			.isNotEqualTo(IdempotencyRequest.of(PRINCIPAL, "S", "k", "a", "1b", "b", "2").requestHash());
	}

	@Test
	void keysMustBeShortVisibleAscii() {
		assertThat(IdempotencyKeys.require("demo-transfer-001", 128)).isEqualTo("demo-transfer-001");
		assertThatThrownBy(() -> IdempotencyKeys.require(null, 128)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED"));
		assertThatThrownBy(() -> IdempotencyKeys.require("a".repeat(129), 128))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY"));
		assertThatThrownBy(() -> IdempotencyKeys.require("has space", 128)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> IdempotencyKeys.require("tab\tkey", 128)).isInstanceOf(ApiException.class);
	}

}
