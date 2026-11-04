package io.github.harsha135135.ledgercore.api;

import java.util.UUID;

import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.support.ApiClient.ApiResponse;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The operational metrics named in the specification are exposed to authenticated operators. */
class MetricsTest extends IntegrationTest {

	@Test
	void replaysRejectionsAndPoolWaitAreMeasured() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		double replaysBefore = metricValue("ledgercore.idempotency.replays");
		String key = TestFixtures.newKey();
		TransferCommand tooLarge = new TransferCommand(source, destination, 5_000);
		ledgerService.transfer(TestUsers.CUSTOMER_A_ID, tooLarge, key, TestFixtures.requestId());
		ledgerService.transfer(TestUsers.CUSTOMER_A_ID, tooLarge, key, TestFixtures.requestId());

		assertThat(metricValue("ledgercore.idempotency.replays")).isEqualTo(replaysBefore + 1);
		assertThat(metricValue("ledgercore.business.rejections")).isGreaterThanOrEqualTo(1);
		for (String name : new String[] { "hikaricp.connections.pending", "hikaricp.connections.acquire",
				"http.server.requests", "ledgercore.outbox.failed.events" }) {
			assertThat(api().get("/actuator/metrics/" + name, TestUsers.ADMIN).status()).as(name).isEqualTo(200);
		}
	}

	private double metricValue(String name) {
		ApiResponse response = api().get("/actuator/metrics/" + name, TestUsers.ADMIN);
		if (response.status() == 404) {
			return 0;
		}
		return response.body().get("measurements").get(0).get("value").asDouble();
	}

}
