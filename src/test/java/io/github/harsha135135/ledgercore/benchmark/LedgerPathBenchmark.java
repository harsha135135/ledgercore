package io.github.harsha135135.ledgercore.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariDataSource;

import io.github.harsha135135.ledgercore.ledger.LedgerRequests.TransferCommand;
import io.github.harsha135135.ledgercore.ledger.MutationResult;
import io.github.harsha135135.ledgercore.support.IntegrationTest;
import io.github.harsha135135.ledgercore.support.PostgresTestDatabase;
import io.github.harsha135135.ledgercore.support.TestFixtures;
import io.github.harsha135135.ledgercore.support.TestUsers;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ledger-path benchmark: calls {@code LedgerService.transfer} directly with an already-authenticated
 * principal, so it includes the current-role and ownership reads, pool acquisition and the full
 * transaction (key claim, locks, journal, balances, audit and outbox), but excludes HTTP parsing,
 * password verification and response serialization. These numbers diagnose ledger contention;
 * they are not end-to-end API capacity.
 *
 * <p>Run explicitly: {@code mvn test -Dexcluded.test.groups=none -Dtest=LedgerPathBenchmark}.
 * Raw results are written to {@code benchmarks/results/}.
 */
@Tag("benchmark")
class LedgerPathBenchmark extends IntegrationTest {

	private static final int[] CONCURRENCY = { 1, 4, 8, 16 };
	private static final int WARMUP_OPERATIONS = Integer.getInteger("bench.warmup", 500);
	private static final int MEASURED_OPERATIONS = Integer.getInteger("bench.operations", 3000);
	private static final int REPETITIONS = Integer.getInteger("bench.repetitions", 3);
	private static final int SPREAD_ACCOUNTS = 64;

	@Autowired
	private HikariDataSource dataSource;

	@Test
	void measureTransfers() throws Exception {
		List<UUID> spreadSources = new ArrayList<>();
		List<UUID> destinations = new ArrayList<>();
		for (int i = 0; i < SPREAD_ACCOUNTS; i++) {
			spreadSources.add(fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000_000_000L));
			destinations.add(fixtures.newAccount(TestUsers.CUSTOMER_B_ID));
		}
		UUID hotSource = fixtures.newFundedAccount(TestUsers.CUSTOMER_A_ID, 1_000_000_000_000L);

		List<Map<String, Object>> results = new ArrayList<>();
		for (String workload : List.of("spread", "hot-source")) {
			List<UUID> sources = workload.equals("spread") ? spreadSources : List.of(hotSource);
			runLevel(sources, destinations, 4, WARMUP_OPERATIONS); // warm-up, discarded
			for (int concurrency : CONCURRENCY) {
				for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
					Map<String, Object> result = runLevel(sources, destinations, concurrency, MEASURED_OPERATIONS);
					result.put("workload", workload);
					result.put("concurrency", concurrency);
					result.put("repetition", repetition);
					results.add(result);
					System.out.printf("BENCH %-10s c=%-2d rep=%d  %8.0f ops/s  p50=%6.2f ms  p95=%6.2f ms  p99=%6.2f ms  errors=%s%n",
							workload, concurrency, repetition, result.get("throughputPerSecond"), result.get("p50Ms"),
							result.get("p95Ms"), result.get("p99Ms"), result.get("errors"));
				}
			}
		}

		Map<String, Object> report = new LinkedHashMap<>();
		report.put("environment", environment());
		report.put("method", Map.of(
				"path", "ledger service (LedgerService.transfer), excluding HTTP and password verification",
				"warmupOperations", WARMUP_OPERATIONS, "measuredOperationsPerRun", MEASURED_OPERATIONS,
				"repetitions", REPETITIONS, "amountPerTransferMinor", 1,
				"spreadWorkload", SPREAD_ACCOUNTS + " source accounts and " + SPREAD_ACCOUNTS
						+ " destinations chosen at random per transfer",
				"hotSourceWorkload", "every transfer debits the same source account; destinations random"));
		report.put("passwordVerification", bcryptCosts());
		report.put("runs", results);
		writeReport(report);
		assertLedgerConsistent();
	}

	/** Runs {@code operations} transfers on {@code concurrency} threads released together. */
	private Map<String, Object> runLevel(List<UUID> sources, List<UUID> destinations, int concurrency, int operations)
			throws Exception {
		long[] latenciesNanos = new long[operations];
		AtomicInteger next = new AtomicInteger();
		AtomicInteger errors = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> workers = new ArrayList<>();
		for (int w = 0; w < concurrency; w++) {
			workers.add(executor.submit(() -> {
				start.await();
				int index;
				while ((index = next.getAndIncrement()) < operations) {
					ThreadLocalRandom random = ThreadLocalRandom.current();
					UUID source = sources.get(random.nextInt(sources.size()));
					UUID destination = destinations.get(random.nextInt(destinations.size()));
					long started = System.nanoTime();
					try {
						MutationResult result = ledgerService.transfer(TestUsers.CUSTOMER_A_ID,
								new TransferCommand(source, destination, 1), TestFixtures.newKey(), "bench");
						if (result.httpStatus() != 201) {
							errors.incrementAndGet();
						}
					}
					catch (RuntimeException ex) {
						errors.incrementAndGet();
					}
					latenciesNanos[index] = System.nanoTime() - started;
				}
				return null;
			}));
		}
		long runStarted = System.nanoTime();
		start.countDown();
		for (Future<?> worker : workers) {
			worker.get();
		}
		long elapsedNanos = System.nanoTime() - runStarted;
		executor.shutdown();

		long[] sorted = latenciesNanos.clone();
		Arrays.sort(sorted);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("operations", operations);
		result.put("elapsedMs", round(elapsedNanos / 1e6));
		result.put("throughputPerSecond", round(operations / (elapsedNanos / 1e9)));
		result.put("p50Ms", millis(sorted, 0.50));
		result.put("p95Ms", millis(sorted, 0.95));
		result.put("p99Ms", millis(sorted, 0.99));
		result.put("maxMs", round(sorted[sorted.length - 1] / 1e6));
		result.put("errors", errors.get());
		return result;
	}

	/** Median verification time for one password at each cost, on this JVM. */
	private static List<Map<String, Object>> bcryptCosts() {
		List<Map<String, Object>> costs = new ArrayList<>();
		for (int cost : new int[] { TestUsers.TEST_BCRYPT_STRENGTH, 10 }) {
			BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(cost);
			String hash = encoder.encode("benchmark-password");
			long[] samples = new long[15];
			for (int i = 0; i < samples.length; i++) {
				long started = System.nanoTime();
				encoder.matches("benchmark-password", hash);
				samples[i] = System.nanoTime() - started;
			}
			Arrays.sort(samples);
			costs.add(Map.of("cost", cost, "medianVerifyMs", round(samples[samples.length / 2] / 1e6)));
		}
		return costs;
	}

	private Map<String, Object> environment() {
		Map<String, Object> environment = new LinkedHashMap<>();
		environment.put("timestampUtc", Instant.now().toString());
		environment.put("javaVersion", System.getProperty("java.version"));
		environment.put("javaVendor", System.getProperty("java.vendor"));
		environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
				+ System.getProperty("os.arch"));
		environment.put("hostLogicalProcessors", Runtime.getRuntime().availableProcessors());
		environment.put("postgresImage", PostgresTestDatabase.imageName());
		environment.put("postgresVersion", jdbc.queryForObject("SELECT version()", String.class));
		environment.put("synchronousCommit", jdbc.queryForObject("SHOW synchronous_commit", String.class));
		environment.put("fsync", jdbc.queryForObject("SHOW fsync", String.class));
		environment.put("connectionPoolSize", dataSource.getMaximumPoolSize());
		try {
			var info = DockerClientFactory.instance().client().infoCmd().exec();
			environment.put("dockerServer", info.getOperatingSystem() + ", " + info.getNCPU() + " CPUs, "
					+ (info.getMemTotal() == null ? "?" : info.getMemTotal() / (1024 * 1024)) + " MiB");
		}
		catch (RuntimeException ex) {
			environment.put("dockerServer", "unavailable: " + ex.getClass().getSimpleName());
		}
		environment.put("note", "PostgreSQL runs in a Testcontainers container; on macOS that is inside the "
				+ "Docker Desktop Linux VM, so disk and CPU behaviour differ from a native Linux server.");
		environment.put("sourceRevision", "unversioned working tree (no git repository at measurement time)");
		return environment;
	}

	private static void writeReport(Map<String, Object> report) throws IOException {
		Path directory = Path.of("benchmarks", "results");
		Files.createDirectories(directory);
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
		Path file = directory.resolve("ledger-path-" + stamp + ".json");
		JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
		Files.writeString(file, mapper.writeValueAsString(report));
		System.out.println("BENCH raw results: " + file.toAbsolutePath());
	}

	private static double millis(long[] sortedNanos, double quantile) {
		int index = (int) Math.ceil(quantile * sortedNanos.length) - 1;
		return round(sortedNanos[Math.max(0, index)] / 1e6);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

}
