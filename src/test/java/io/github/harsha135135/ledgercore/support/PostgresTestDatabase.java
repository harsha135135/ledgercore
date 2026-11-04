package io.github.harsha135135.ledgercore.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real PostgreSQL container for the whole test run, started before the Spring context.
 * The container's default user owns the schema and runs Flyway; the application connects as
 * ledger_app, exactly as in production, so permission tests exercise real grants.
 */
public final class PostgresTestDatabase {

	public static final String APP_USER = "ledger_app";
	public static final String APP_PASSWORD = "ledger-app-test-password";

	private static final PostgreSQLContainer CONTAINER = startContainer();

	private PostgresTestDatabase() {
	}

	private static PostgreSQLContainer startContainer() {
		String image = System.getProperty("postgres.test.image", "postgres:17.11");
		// Testcontainers starts PostgreSQL with fsync=off by default. Keep durability on so tests and
		// benchmarks run with production commit behaviour.
		PostgreSQLContainer container = new PostgreSQLContainer(DockerImageName.parse(image))
			.withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on");
		container.start();
		// The migration grants privileges to ledger_app; login and password are set outside migrations.
		try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(),
				container.getPassword()); Statement statement = connection.createStatement()) {
			statement.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "'");
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Could not create the application role", ex);
		}
		return container;
	}

	public static String jdbcUrl() {
		return CONTAINER.getJdbcUrl();
	}

	public static String ownerUser() {
		return CONTAINER.getUsername();
	}

	public static String ownerPassword() {
		return CONTAINER.getPassword();
	}

	/** A connection outside the application pool, as the schema owner. Used by observers and fixtures. */
	public static Connection openOwnerConnection() throws SQLException {
		return DriverManager.getConnection(jdbcUrl(), ownerUser(), ownerPassword());
	}

	/** A connection as the application role, outside the pool, for permission tests. */
	public static Connection openAppConnection() throws SQLException {
		return DriverManager.getConnection(jdbcUrl(), APP_USER, APP_PASSWORD);
	}

	public static String imageName() {
		return CONTAINER.getDockerImageName();
	}

}
