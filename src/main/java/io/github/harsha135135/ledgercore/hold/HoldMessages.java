package io.github.harsha135135.ledgercore.hold;

import java.util.UUID;

import io.github.harsha135135.ledgercore.api.ApiException;
import io.github.harsha135135.ledgercore.api.Ids;
import io.github.harsha135135.ledgercore.ledger.LedgerResponses;
import io.github.harsha135135.ledgercore.ledger.Money;

/** Request and response bodies for holds. */
public final class HoldMessages {

	private HoldMessages() {
	}

	public record CreateHoldRequest(String sourceAccountId, String destinationAccountId, String amountMinor,
			String currency) {
	}

	public record CreateHoldCommand(UUID sourceAccountId, UUID destinationAccountId, long amountMinor) {

		public static CreateHoldCommand parse(CreateHoldRequest request, long maxAmountMinor) {
			UUID source = Ids.parseField(request.sourceAccountId(), "sourceAccountId");
			UUID destination = Ids.parseField(request.destinationAccountId(), "destinationAccountId");
			if (source.equals(destination)) {
				throw ApiException.badRequest("SAME_ACCOUNT", "Source and destination must be different accounts.");
			}
			long amount = Money.parsePositiveAmount(request.amountMinor(), maxAmountMinor);
			Money.requireSupportedCurrency(request.currency());
			return new CreateHoldCommand(source, destination, amount);
		}
	}

	public record HoldResponse(String holdId, String sourceAccountId, String destinationAccountId,
			String amountMinor, String currency, String state, String captureJournalId, String createdAt,
			String closedAt, String requestId) {

		public static HoldResponse from(HoldRow hold, String requestId) {
			return new HoldResponse(hold.id().toString(), hold.sourceId().toString(), hold.destinationId().toString(),
					Money.format(hold.amountMinor()), Money.CURRENCY, hold.state().name(),
					hold.captureJournalId() == null ? null : hold.captureJournalId().toString(),
					LedgerResponses.timestamp(hold.createdAt()), LedgerResponses.timestamp(hold.closedAt()),
					requestId);
		}
	}

}
