package io.github.harsha135135.ledgercore.api;

import java.util.UUID;

import io.github.harsha135135.ledgercore.support.ApiClient;
import io.github.harsha135135.ledgercore.support.ApiClient.ApiResponse;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetailsService;

import static io.github.harsha135135.ledgercore.support.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

/** HTTP contract and security behaviour, through real HTTP and real password checks. */
class HttpSecurityTest extends IntegrationTest {

	@Autowired
	private UserDetailsService userDetailsService;

	@Test
	void missingCredentialsGetJson401WithRequestId() {
		ApiResponse response = api().getAnonymous("/v1/accounts/" + UUID.randomUUID());
		assertThat(response.status()).isEqualTo(401);
		assertThat(response.text("code")).isEqualTo("AUTHENTICATION_REQUIRED");
		assertThat(response.text("requestId")).isEqualTo(response.header(RequestIds.HEADER)).isNotBlank();
		assertThat(response.header("WWW-Authenticate")).startsWith("Basic");
	}

	@Test
	void wrongPasswordGets401() {
		ApiResponse response = api().postWithPassword("/v1/transfers", TestUsers.CUSTOMER_A, "wrong-password", "{}");
		assertThat(response.status()).isEqualTo(401);
		assertThat(response.text("code")).isEqualTo("AUTHENTICATION_REQUIRED");
	}

	@Test
	void unprovisionedLoginFailsClosed() {
		ApiResponse response = api().get("/v1/accounts/" + UUID.randomUUID(), TestUsers.UNMAPPED);
		assertThat(response.status()).isEqualTo(403);
		assertThat(response.text("code")).isEqualTo("PRINCIPAL_NOT_PROVISIONED");
	}

	@Test
	void customersCannotUseAdministratorEndpoints() {
		UUID account = fixtures.newAccount(TestUsers.CUSTOMER_A_ID);
		ApiResponse funding = api().post("/v1/demo-funding", TestUsers.CUSTOMER_A,
				json("destinationAccountId", account.toString(), "amountMinor", "100", "currency", "INR", "reason",
						"self-funding attempt"));
		assertThat(funding.status()).isEqualTo(403);
		assertThat(funding.text("code")).isEqualTo("FORBIDDEN_OPERATION");
		assertThat(postedBalance(account)).isZero();

		ApiResponse create = api().post("/v1/accounts", TestUsers.CUSTOMER_A,
				json("ownerId", TestUsers.CUSTOMER_A_ID.toString()));
		assertThat(create.status()).isEqualTo(403);

		assertThat(api().get("/v1/admin/reconciliation", TestUsers.CUSTOMER_A).status()).isEqualTo(403);
	}

	@Test
	void otherCustomersAccountLooksExactlyLikeAMissingOne() {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000);
		ApiResponse foreign = api().get("/v1/accounts/" + account, TestUsers.CUSTOMER_B);
		ApiResponse missing = api().get("/v1/accounts/" + UUID.randomUUID(), TestUsers.CUSTOMER_B);
		assertThat(foreign.status()).isEqualTo(404);
		assertThat(missing.status()).isEqualTo(404);
		assertThat(foreign.text("code")).isEqualTo(missing.text("code")).isEqualTo("RESOURCE_NOT_FOUND");
		assertThat(foreign.text("message")).isEqualTo(missing.text("message"));

		assertThat(api().get("/v1/accounts/" + account + "/balance", TestUsers.CUSTOMER_B).status()).isEqualTo(404);
		assertThat(api().get("/v1/accounts/" + account + "/journals", TestUsers.CUSTOMER_B).status()).isEqualTo(404);
		assertThat(api().get("/v1/accounts/not-a-uuid", TestUsers.CUSTOMER_B).status()).isEqualTo(404);
	}

	@Test
	void ownerAndAdminCanReadAnAccount() {
		UUID account = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 2_500);
		ApiResponse owner = api().get("/v1/accounts/" + account + "/balance", TestUsers.CUSTOMER_A);
		assertThat(owner.status()).isEqualTo(200);
		assertThat(owner.text("postedMinor")).isEqualTo("2500");
		assertThat(owner.text("availableMinor")).isEqualTo("2500");

		ApiResponse admin = api().get("/v1/accounts/" + account, TestUsers.ADMIN);
		assertThat(admin.status()).isEqualTo(200);
		assertThat(admin.text("ownerId")).isEqualTo(TestUsers.CUSTOMER_A_ID.toString());
		assertThat(admin.text("kind")).isEqualTo("CUSTOMER");
	}

	@Test
	void customerCannotSpendFromSomeoneElsesAccount() {
		UUID victim = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 5_000);
		UUID thief = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ApiResponse response = api().post("/v1/transfers", TestUsers.CUSTOMER_B, transfer(victim, thief, "1000"));
		assertThat(response.status()).isEqualTo(404);
		assertThat(postedBalance(victim)).isEqualTo(5_000);
		assertThat(postedBalance(thief)).isZero();
	}

	@Test
	void adminDoesNotBypassOwnershipForOrdinaryTransfers() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 5_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ApiResponse response = api().post("/v1/transfers", TestUsers.ADMIN, transfer(source, destination, "1000"));
		assertThat(response.status()).isEqualTo(404);
		assertThat(postedBalance(source)).isEqualTo(5_000);
	}

	@Test
	void transferSucceedsOverHttpWithStringMoney() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 20_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ApiResponse response = api().post("/v1/transfers", TestUsers.CUSTOMER_A, transfer(source, destination, "12500"));
		assertThat(response.status()).isEqualTo(201);
		assertThat(response.text("kind")).isEqualTo("TRANSFER");
		assertThat(response.text("amountMinor")).isEqualTo("12500");
		assertThat(response.text("currency")).isEqualTo("INR");
		assertThat(response.text("requestId")).isEqualTo(response.header(RequestIds.HEADER));
		assertThat(postedBalance(source)).isEqualTo(7_500);
		assertThat(postedBalance(destination)).isEqualTo(12_500);
	}

	@Test
	void malformedPayloadsAreRejectedBeforeAnyMoneyMoves() {
		UUID source = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 10_000);
		UUID destination = fixtures.newAccount(TestUsers.CUSTOMER_B_ID);
		ApiClient api = api();

		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A, transfer(source, destination, "125.00")), 400,
				"INVALID_AMOUNT");
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A, transfer(source, destination, "-5")), 400,
				"INVALID_AMOUNT");
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A, transfer(source, source, "5")), 400,
				"SAME_ACCOUNT");
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A,
				json("sourceAccountId", source.toString(), "destinationAccountId", destination.toString(),
						"amountMinor", "5", "currency", "USD")),
				422, "UNSUPPORTED_CURRENCY");
		// A JSON number is not accepted where the contract requires a decimal string.
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A,
				"{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
						+ "\",\"amountMinor\":5,\"currency\":\"INR\"}"),
				400, "MALFORMED_REQUEST");
		// Unknown and duplicated fields are rejected rather than silently ignored.
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A,
				"{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
						+ "\",\"amountMinor\":\"5\",\"currency\":\"INR\",\"memo\":\"x\"}"),
				400, "MALFORMED_REQUEST");
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A,
				"{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
						+ "\",\"amountMinor\":\"5\",\"amountMinor\":\"9000\",\"currency\":\"INR\"}"),
				400, "MALFORMED_REQUEST");
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A, "not json"), 400, "MALFORMED_REQUEST");
		assertError(api.post("/v1/transfers", TestUsers.CUSTOMER_A,
				json("sourceAccountId", "1-1-1-1-1", "destinationAccountId", destination.toString(), "amountMinor",
						"5", "currency", "INR")),
				400, "INVALID_IDENTIFIER");

		assertThat(postedBalance(source)).isEqualTo(10_000);
		assertThat(journalCountFor(destination)).isZero();
	}

	@Test
	void oversizedBodiesGet413() {
		String padding = "x".repeat(20_000);
		ApiResponse response = api().post("/v1/transfers", TestUsers.CUSTOMER_A,
				json("sourceAccountId", padding, "destinationAccountId", "y", "amountMinor", "1", "currency", "INR"));
		assertError(response, 413, "REQUEST_TOO_LARGE");
	}

	/**
	 * Regression: Spring Security re-hashes weaker hashes at its default cost after a successful
	 * login when the user store allows it. That silently raised the cost verified on every request
	 * (about 93 ms per request on the development Mac). The configured hash must stay in use.
	 */
	@Test
	void configuredPasswordHashIsNotSilentlyUpgradedAfterLogin() {
		assertThat(api().get("/v1/accounts/" + UUID.randomUUID(), TestUsers.CUSTOMER_A).status()).isEqualTo(404);
		assertThat(api().get("/v1/accounts/" + UUID.randomUUID(), TestUsers.CUSTOMER_A).status()).isEqualTo(404);
		String stored = userDetailsService.loadUserByUsername(TestUsers.CUSTOMER_A).getPassword();
		assertThat(stored).startsWith("{bcrypt}$2a$0" + TestUsers.TEST_BCRYPT_STRENGTH + "$");
	}

	@Test
	void healthIsPublicButMetricsAreNot() {
		assertThat(api().getAnonymous("/actuator/health/readiness").status()).isEqualTo(200);
		assertThat(api().getAnonymous("/actuator/metrics").status()).isEqualTo(401);
	}

	static String transfer(UUID source, UUID destination, String amountMinor) {
		return json("sourceAccountId", source.toString(), "destinationAccountId", destination.toString(),
				"amountMinor", amountMinor, "currency", "INR");
	}

	private static void assertError(ApiResponse response, int status, String code) {
		assertThat(response.status()).as(response.rawBody()).isEqualTo(status);
		assertThat(response.text("code")).isEqualTo(code);
		assertThat(response.text("requestId")).isNotBlank();
		assertThat(response.rawBody()).doesNotContainIgnoringCase("select ").doesNotContain("SQLState");
	}

}
