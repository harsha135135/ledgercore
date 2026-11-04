package io.github.harsha135135.ledgercore.ledger;

import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.api.Ids;

/**
 * JSON request bodies and the validated commands parsed from them. Every field arrives as a
 * string and is checked here before a service sees it.
 */
public final class LedgerRequests {

	private static final int MAX_REASON_LENGTH = 200;

	private LedgerRequests() {
	}

	public record CreateAccountRequest(String ownerId) {
	}

	public record FundingRequest(String destinationAccountId, String amountMinor, String currency, String reason) {
	}

	public record TransferRequest(String sourceAccountId, String destinationAccountId, String amountMinor,
			String currency) {
	}

	public record ReasonRequest(String reason) {
	}

	public record FundingCommand(UUID destinationAccountId, long amountMinor, String reason) {

		public static FundingCommand parse(FundingRequest request, long maxAmountMinor) {
			UUID destination = Ids.parseField(request.destinationAccountId(), "destinationAccountId");
			long amount = Money.parsePositiveAmount(request.amountMinor(), maxAmountMinor);
			Money.requireSupportedCurrency(request.currency());
			return new FundingCommand(destination, amount, requireReason(request.reason()));
		}
	}

	public record TransferCommand(UUID sourceAccountId, UUID destinationAccountId, long amountMinor) {

		public static TransferCommand parse(TransferRequest request, long maxAmountMinor) {
			UUID source = Ids.parseField(request.sourceAccountId(), "sourceAccountId");
			UUID destination = Ids.parseField(request.destinationAccountId(), "destinationAccountId");
			if (source.equals(destination)) {
				throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination must be different accounts.");
			}
			long amount = Money.parsePositiveAmount(request.amountMinor(), maxAmountMinor);
			Money.requireSupportedCurrency(request.currency());
			return new TransferCommand(source, destination, amount);
		}
	}

	/** Privileged actions carry a human reason into the audit trail. */
	public static String requireReason(String reason) {
		if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
			throw ApiException.badRequest("INVALID_REASON",
					"reason is required and must be at most " + MAX_REASON_LENGTH + " characters.");
		}
		return reason.strip();
	}

}
