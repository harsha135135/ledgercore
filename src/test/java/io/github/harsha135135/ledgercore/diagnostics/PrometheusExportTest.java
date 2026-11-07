package io.github.harsha135135.ledgercore.diagnostics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.harsha135135.ledgercore.consumer.DemoConsumer;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.outbox.OutboxPoller;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Prometheus export exists, uses the names IncidentLens queries, has histogram buckets, carries
 * no identifiers in labels, and is readable only with the diagnostics credential. That credential
 * cannot reach anything else.
 *
 * <p>Spring Boot turns metrics export off in tests unless asked, so this class opts in. That gives it
 * its own application context; the other tests keep the shared one.
 */
@AutoConfigureMetrics
class PrometheusExportTest extends IntegrationTest {

	private static final Pattern UUID_TEXT = Pattern
		.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@Autowired
	private OutboxPoller poller;

	@Autowired
	private DemoConsumer consumer;

	@Test
	void exportHasTheNamesAndBucketsIncidentLensUses() throws Exception {
		produceTraffic();
		Scrape scrape = scrape(TestUsers.DIAGNOSTICS);

		assertThat(scrape.status()).isEqualTo(200);
		assertThat(scrape.body()).contains(
				"http_server_requests_seconds_bucket{",
				"http_server_requests_seconds_count{",
				"ledgercore_outbox_pending_events ",
				"ledgercore_outbox_failed_events ",
				"ledgercore_outbox_oldest_undelivered_age_seconds ",
				"hikaricp_connections_active{",
				"hikaricp_connections_max{",
				"hikaricp_connections_pending{",
				"hikaricp_connections_timeout_total{",
				"ledgercore_outbox_delivery_seconds_bucket{",
				"ledgercore_outbox_poll_seconds_bucket{");
		// Buckets are the configured fixed boundaries, exported in seconds.
		assertThat(scrape.body()).contains("le=\"0.005\"", "le=\"0.25\"", "le=\"10.0\"");
		// Route templates, not concrete paths.
		assertThat(scrape.body()).contains("uri=\"/v1/accounts/{accountId}\"");
	}

	@Test
	void metricLabelsCarryNoIdentifiers() throws Exception {
		produceTraffic();
		String body = scrape(TestUsers.DIAGNOSTICS).body();
		assertThat(UUID_TEXT.matcher(body).find()).as("a UUID appears in the Prometheus export").isFalse();
	}

	@Test
	void onlyTheDiagnosticsCredentialCanReadTheExport() throws Exception {
		assertThat(scrape(null).status()).isEqualTo(401);
		assertThat(scrape(TestUsers.CUSTOMER_A).status()).isEqualTo(403);
		assertThat(scrape(TestUsers.ADMIN).status()).isEqualTo(403);
		assertThat(scrape(TestUsers.PUBLISHER).status()).isEqualTo(403);
	}

	@Test
	void diagnosticsCredentialCannotReachTheLedgerOrOtherEndpoints() {
		assertThat(api().get("/v1/accounts/" + UUID.randomUUID(), TestUsers.DIAGNOSTICS).status()).isEqualTo(403);
		assertThat(api().get("/v1/admin/reconciliation", TestUsers.DIAGNOSTICS).status()).isEqualTo(403);
		assertThat(api().post("/v1/transfers", TestUsers.DIAGNOSTICS, "{}").status()).isEqualTo(403);
		assertThat(api().get("/actuator/metrics", TestUsers.DIAGNOSTICS).status()).isEqualTo(403);
		assertThat(api().get("/demo-consumer/counts?eventType=x", TestUsers.DIAGNOSTICS).status()).isEqualTo(403);
		// Existing operator access to /actuator/metrics is unchanged.
		assertThat(api().get("/actuator/metrics", TestUsers.ADMIN).status()).isEqualTo(200);
	}

	/** One HTTP read of an account and one delivered outbox event, so every timer has samples. */
	private void produceTraffic() {
		consumer.setAccepting(true);
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 5_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ledgerService.transfer(TestUsers.CUSTOMER_A_ID, new TransferCommand(source, destination, 100),
				TestFixtures.newKey(), TestFixtures.requestId());
		poller.pollOnce();
		assertThat(api().get("/v1/accounts/" + source, TestUsers.CUSTOMER_A).status()).isEqualTo(200);
	}

	private Scrape scrape(String username) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"));
		if (username != null) {
			String token = username + ":" + TestUsers.password(username);
			request.header("Authorization",
					"Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
		}
		HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
		return new Scrape(response.statusCode(), response.body());
	}

	private record Scrape(int status, String body) {
	}

}
