package io.github.harsha135135.ledgercore.ledger;

import java.util.regex.Pattern;

import io.github.harsha135135.ledgercore.api.ApiException;

/**
 * Money is a signed 64-bit count of minor units (paise): 12500 means ₹125.00.
 * The API carries amounts as decimal strings so JavaScript clients cannot lose precision.
 * Floating point is never used.
 */
public final class Money {

	public static final String CURRENCY = "INR";

	/** A positive whole number with no sign, decimal point, exponent, spaces or leading zero. */
	private static final Pattern POSITIVE_WHOLE_NUMBER = Pattern.compile("^[1-9][0-9]*$");

	/** 10^12 has 13 digits; anything longer is over the demo limit without parsing. */
	private static final int MAX_DIGITS = 13;

	private Money() {
	}

	/** Parses a requested amount. Malformed input is a 400; a well-formed amount over the limit is a 422. */
	public static long parsePositiveAmount(String raw, long maxAmountMinor) {
		if (raw == null || !POSITIVE_WHOLE_NUMBER.matcher(raw).matches()) {
			throw ApiException.badRequest("INVALID_AMOUNT",
					"amountMinor must be a positive whole number of minor units, as a string with no sign, "
							+ "decimal point or leading zero.");
		}
		if (raw.length() > MAX_DIGITS) {
			throw amountOverLimit(maxAmountMinor);
		}
		long amount = Long.parseLong(raw);
		if (amount > maxAmountMinor) {
			throw amountOverLimit(maxAmountMinor);
		}
		return amount;
	}

	/** Only INR is configured. A missing currency is malformed; a different one is unsupported. */
	public static void requireSupportedCurrency(String currency) {
		if (currency == null || currency.isEmpty()) {
			throw ApiException.badRequest("INVALID_CURRENCY", "currency is required.");
		}
		if (!CURRENCY.equals(currency)) {
			throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "Only INR is supported.");
		}
	}

	public static String format(long amountMinor) {
		return Long.toString(amountMinor);
	}

	private static ApiException amountOverLimit(long maxAmountMinor) {
		return ApiException.unprocessable("AMOUNT_LIMIT_EXCEEDED",
				"amountMinor must not exceed " + maxAmountMinor + ".");
	}

}
