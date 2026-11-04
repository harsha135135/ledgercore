package io.github.harsha135135.ledgercore.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import io.github.harsha135135.ledgercore.ledger.AccountService;
import io.github.harsha135135.ledgercore.ledger.LedgerRequests.FundingCommand;
import io.github.harsha135135.ledgercore.ledger.LedgerService;
import io.github.harsha135135.ledgercore.ledger.MutationResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * Builds fresh accounts through the real services, so fixtures obey every ledger rule and each
 * test works on its own isolated balances.
 */
public class TestFixtures {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final AccountService accountService;
	private final LedgerService ledgerService;

	public TestFixtures(AccountService accountService, LedgerService ledgerService) {
		this.accountService = accountService;
		this.ledgerService = ledgerService;
	}

	public UUID newAccount(UUID ownerId) {
		MutationResult result = accountService.createAccount(TestUsers.ADMIN_ID, ownerId, newKey(), requestId());
		requireSuccess(result);
		return UUID.fromString(field(result, "accountId"));
	}

	/**
	 * Creates a zero-balance customer account with a chosen ID, as the schema owner. Used to place
	 * accounts on both sides of the hex-8 boundary where signed and canonical UUID orders differ.
	 */
	public static UUID newAccountWithId(UUID accountId, UUID ownerId) throws SQLException {
		try (Connection connection = PostgresTestDatabase.openOwnerConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO accounts (id, owner_id, kind, currency, state) VALUES (?, ?, 'CUSTOMER', 'INR', 'ACTIVE')")) {
			statement.setObject(1, accountId);
			statement.setObject(2, ownerId);
			statement.executeUpdate();
		}
		return accountId;
	}

	/** A random UUID whose first hex digit is {@code firstHexDigit}. */
	public static UUID uuidStartingWith(char firstHexDigit) {
		return UUID.fromString(firstHexDigit + UUID.randomUUID().toString().substring(1));
	}

	public static String newKey() {
		return "test-" + UUID.randomUUID();
	}

	public UUID newFundedAccount(UUID ownerId, long amountMinor) {
		UUID accountId = newAccount(ownerId);
		fund(accountId, amountMinor);
		return accountId;
	}

	public String fund(UUID accountId, long amountMinor) {
		MutationResult result = ledgerService.fund(TestUsers.ADMIN_ID,
				new FundingCommand(accountId, amountMinor, "test fixture"), newKey(), requestId());
		requireSuccess(result);
		return field(result, "journalId");
	}

	public static String field(MutationResult result, String name) {
		return JSON.readTree(result.bodyJson()).get(name).asString();
	}

	public static String requestId() {
		return UUID.randomUUID().toString();
	}

	private static void requireSuccess(MutationResult result) {
		if (!result.isSuccess()) {
			throw new AssertionError("Fixture step failed: " + result.httpStatus() + " " + result.bodyJson());
		}
	}

	/** Commits a role change, as an operator would, through the schema owner connection. */
	public static void setRole(UUID principalId, String role) throws SQLException {
		try (Connection connection = PostgresTestDatabase.openOwnerConnection();
				PreparedStatement statement = connection.prepareStatement("UPDATE principals SET role = ? WHERE id = ?")) {
			statement.setString(1, role);
			statement.setObject(2, principalId);
			statement.executeUpdate();
		}
	}

}
