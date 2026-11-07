package io.github.harsha135135.ledgercore.support;

import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test logins. Hashes are generated at startup, so no credential is stored in the repository.
 * BCrypt strength 4 (the minimum) keeps the HTTP suite fast; correctness does not depend on it,
 * and the HTTP measurement path must record the real cost setting separately.
 */
public final class TestUsers {

	public static final int TEST_BCRYPT_STRENGTH = 4;

	public static final String CUSTOMER_A = "customer-a";
	public static final String CUSTOMER_B = "customer-b";
	public static final String ADMIN = "demo-admin";
	/** Authenticates, but its subject has no principal row: requests must fail closed. */
	public static final String UNMAPPED = "unmapped-user";
	/** The outbox publisher's service credential, accepted only by the demonstration consumer. */
	public static final String PUBLISHER = "outbox-publisher";
	/** The Prometheus scrape credential: may read /actuator/prometheus only. */
	public static final String DIAGNOSTICS = "diagnostics-reader";

	public static final UUID CUSTOMER_A_ID = UUID.fromString("a0000000-0000-4000-8000-00000000000a");
	public static final UUID CUSTOMER_B_ID = UUID.fromString("b0000000-0000-4000-8000-00000000000b");
	public static final UUID ADMIN_ID = UUID.fromString("ad000000-0000-4000-8000-0000000000ad");

	private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(TEST_BCRYPT_STRENGTH);
	private static final String HASH_A = "{bcrypt}" + ENCODER.encode(password(CUSTOMER_A));
	private static final String HASH_B = "{bcrypt}" + ENCODER.encode(password(CUSTOMER_B));
	private static final String HASH_ADMIN = "{bcrypt}" + ENCODER.encode(password(ADMIN));
	private static final String HASH_UNMAPPED = "{bcrypt}" + ENCODER.encode(password(UNMAPPED));
	private static final String HASH_PUBLISHER = "{bcrypt}" + ENCODER.encode(password(PUBLISHER));
	private static final String HASH_DIAGNOSTICS = "{bcrypt}" + ENCODER.encode(password(DIAGNOSTICS));

	private TestUsers() {
	}

	public static String password(String username) {
		return username + "-test-password";
	}

	/** Registers complete entries: an indexed list from a higher-priority source replaces the YAML list. */
	static void register(DynamicPropertyRegistry registry) {
		addUser(registry, 0, CUSTOMER_A, "local:customer-a", HASH_A);
		addUser(registry, 1, CUSTOMER_B, "local:customer-b", HASH_B);
		addUser(registry, 2, ADMIN, "local:demo-admin", HASH_ADMIN);
		addUser(registry, 3, UNMAPPED, "local:nobody", HASH_UNMAPPED);
		registry.add("ledgercore.demo-consumer.username", () -> PUBLISHER);
		registry.add("ledgercore.demo-consumer.password-hash", () -> HASH_PUBLISHER);
		registry.add("ledgercore.outbox.publisher-username", () -> PUBLISHER);
		registry.add("ledgercore.outbox.publisher-password", () -> password(PUBLISHER));
		registry.add("ledgercore.diagnostics.username", () -> DIAGNOSTICS);
		registry.add("ledgercore.diagnostics.password-hash", () -> HASH_DIAGNOSTICS);
	}

	private static void addUser(DynamicPropertyRegistry registry, int index, String username, String subject,
			String hash) {
		String prefix = "ledgercore.security.users[" + index + "].";
		registry.add(prefix + "username", () -> username);
		registry.add(prefix + "subject", () -> subject);
		registry.add(prefix + "password-hash", () -> hash);
	}

}
