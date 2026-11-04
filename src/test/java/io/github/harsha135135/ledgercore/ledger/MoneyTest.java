package io.github.harsha135135.ledgercore.ledger;

import io.github.harsha135135.ledgercore.api.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

	private static final long LIMIT = 1_000_000_000_000L;

	@Test
	void parsesPositiveWholeMinorUnits() {
		assertThat(Money.parsePositiveAmount("1", LIMIT)).isEqualTo(1);
		assertThat(Money.parsePositiveAmount("12500", LIMIT)).isEqualTo(12_500);
		assertThat(Money.parsePositiveAmount("1000000000000", LIMIT)).isEqualTo(LIMIT);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "0", "-5", "+5", "125.00", "1e3", "007", " 5", "5 ", "1,000", "०५", "0x10" })
	void rejectsMalformedAmountsAsBadRequest(String raw) {
		assertThatThrownBy(() -> Money.parsePositiveAmount(raw, LIMIT)).isInstanceOfSatisfying(ApiException.class,
				ex -> {
					assertThat(ex.status().value()).isEqualTo(400);
					assertThat(ex.code()).isEqualTo("INVALID_AMOUNT");
				});
	}

	@Test
	void rejectsMissingAmount() {
		assertThatThrownBy(() -> Money.parsePositiveAmount(null, LIMIT)).isInstanceOf(ApiException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "1000000000001", "99999999999999", "99999999999999999999999" })
	void rejectsAmountsOverTheLimitAsUnprocessable(String raw) {
		assertThatThrownBy(() -> Money.parsePositiveAmount(raw, LIMIT)).isInstanceOfSatisfying(ApiException.class,
				ex -> {
					assertThat(ex.status().value()).isEqualTo(422);
					assertThat(ex.code()).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
				});
	}

	@Test
	void acceptsOnlyInr() {
		Money.requireSupportedCurrency("INR");
		assertThatThrownBy(() -> Money.requireSupportedCurrency("USD"))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(422));
		assertThatThrownBy(() -> Money.requireSupportedCurrency("inr"))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(422));
		assertThatThrownBy(() -> Money.requireSupportedCurrency(null))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status().value()).isEqualTo(400));
	}

}
