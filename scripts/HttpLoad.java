import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Concurrent HTTP load against a running LedgerCore instance (JDK only, no dependencies).
 *
 * <p>Measures client-observed latency and throughput through the real API, including HTTP Basic
 * password verification at whatever cost the server is configured with. Uses HTTP/1.1 with
 * keep-alive connections reused by the JDK client. Afterwards it samples the outbox backlog until
 * it drains, to measure delivery lag.
 *
 * <p>Usage: {@code java scripts/HttpLoad.java [baseUrl]} from the project directory, with the app
 * started through scripts/run-local.sh using the demo credentials from .env.example.
 */
public class HttpLoad {

	private static final int[] CONCURRENCY = { 1, 4, 8, 16, 32 };
	private static final int REPETITIONS = 3;
	private static final int WARMUP_OPERATIONS = 100;
	private static final int ACCOUNT_PAIRS = 32;
	private static final Pattern ACCOUNT_ID = Pattern.compile("\"accountId\":\"([0-9a-f-]{36})\"");
	private static final Pattern GAUGE_VALUE = Pattern.compile("\"value\":([0-9.Ee+-]+)");

	private static final String ADMIN = basic("demo-admin", "admin-demo");
	private static final String CUSTOMER_A = basic("customer-a", "customer-a-demo");
	private static final String CUSTOMER_A_ID = "a0000000-0000-4000-8000-00000000000a";
	private static final String CUSTOMER_B_ID = "b0000000-0000-4000-8000-00000000000b";

	private final String baseUrl;
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final StringBuilder csv = new StringBuilder("workload,concurrency,repetition,request,http_status,latency_ms\n");

	HttpLoad(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public static void main(String[] args) throws Exception {
		new HttpLoad(args.length > 0 ? args[0] : "http://127.0.0.1:8080").run();
	}

	private void run() throws Exception {
		String prediction = "BCrypt cost 10 takes about 89 ms of CPU per request. With 12 cores shared by the "
				+ "client, the server and the Docker VM, throughput should plateau below 12 / 0.089 = ~135 "
				+ "requests/s, probably near 100, for both endpoints; latency then grows roughly linearly with "
				+ "concurrency. The single outbox poller should deliver at most ~11 events/s, so the backlog "
				+ "after the transfer runs should be roughly transfers minus deliveries.";
		System.out.println("Prediction (written before measuring): " + prediction);

		List<String> sources = new ArrayList<>();
		List<String> destinations = new ArrayList<>();
		for (int i = 0; i < ACCOUNT_PAIRS; i++) {
			sources.add(createAccount(CUSTOMER_A_ID, "src-" + i));
			destinations.add(createAccount(CUSTOMER_B_ID, "dst-" + i));
			fund(sources.get(i), i);
		}
		double backlogBeforeLoad = gauge("ledgercore.outbox.pending.events");

		List<String> summaries = new ArrayList<>();
		for (String workload : List.of("balance", "transfer")) {
			runLevel(workload, 4, 0, WARMUP_OPERATIONS, sources, destinations); // warm-up, discarded
			for (int concurrency : CONCURRENCY) {
				for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
					int operations = Math.max(100, 50 * concurrency);
					summaries.add(runLevel(workload, concurrency, repetition, operations, sources, destinations));
				}
			}
		}

		String outbox = sampleOutboxDrain(backlogBeforeLoad);
		writeResults(prediction, summaries, outbox);
	}

	/** Runs {@code operations} requests on {@code concurrency} threads released together. */
	private String runLevel(String workload, int concurrency, int repetition, int operations, List<String> sources,
			List<String> destinations) throws Exception {
		double[] latencies = new double[operations];
		int[] statuses = new int[operations];
		AtomicInteger next = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> workers = new ArrayList<>();
		for (int w = 0; w < concurrency; w++) {
			workers.add(executor.submit(() -> {
				start.await();
				int index;
				while ((index = next.getAndIncrement()) < operations) {
					ThreadLocalRandom random = ThreadLocalRandom.current();
					String source = sources.get(random.nextInt(sources.size()));
					HttpRequest request = workload.equals("balance")
							? get("/v1/accounts/" + source + "/balance", CUSTOMER_A)
							: post("/v1/transfers", CUSTOMER_A, "load-" + runId + "-" + concurrency + "-" + repetition + "-" + index,
									"{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\""
											+ destinations.get(random.nextInt(destinations.size()))
											+ "\",\"amountMinor\":\"1\",\"currency\":\"INR\"}");
					long started = System.nanoTime();
					int status;
					try {
						status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
					}
					catch (IOException ex) {
						status = -1;
					}
					latencies[index] = (System.nanoTime() - started) / 1e6;
					statuses[index] = status;
				}
				return null;
			}));
		}
		long runStarted = System.nanoTime();
		start.countDown();
		for (Future<?> worker : workers) {
			worker.get();
		}
		double elapsedSeconds = (System.nanoTime() - runStarted) / 1e9;
		executor.shutdown();
		if (repetition == 0) {
			return null;
		}

		int non2xx = 0;
		for (int i = 0; i < operations; i++) {
			csv.append(workload).append(',').append(concurrency).append(',').append(repetition).append(',').append(i)
				.append(',').append(statuses[i]).append(',').append(String.format(Locale.ROOT, "%.2f", latencies[i]))
				.append('\n');
			if (statuses[i] < 200 || statuses[i] >= 300) {
				non2xx++;
			}
		}
		double[] sorted = latencies.clone();
		Arrays.sort(sorted);
		double throughput = operations / elapsedSeconds;
		System.out.printf(Locale.ROOT, "LOAD %-8s c=%-2d rep=%d  %6.1f req/s  p50=%7.1f ms  p95=%7.1f ms  p99=%7.1f ms  non2xx=%d%n",
				workload, concurrency, repetition, throughput, quantile(sorted, 0.50), quantile(sorted, 0.95),
				quantile(sorted, 0.99), non2xx);
		return String.format(Locale.ROOT,
				"{\"workload\":\"%s\",\"concurrency\":%d,\"repetition\":%d,\"operations\":%d,\"elapsedSeconds\":%.2f,"
						+ "\"throughputPerSecond\":%.1f,\"p50Ms\":%.1f,\"p95Ms\":%.1f,\"p99Ms\":%.1f,\"maxMs\":%.1f,\"non2xx\":%d}",
				workload, concurrency, repetition, operations, elapsedSeconds, throughput, quantile(sorted, 0.50),
				quantile(sorted, 0.95), quantile(sorted, 0.99), sorted[sorted.length - 1], non2xx);
	}

	/** Samples the outbox gauges every 2 s until nothing is pending (each sample costs one password check). */
	private String sampleOutboxDrain(double backlogBeforeLoad) throws Exception {
		List<String> samples = new ArrayList<>();
		long started = System.nanoTime();
		double pendingAtStop = -1;
		double drainSeconds = -1;
		while (true) {
			double seconds = (System.nanoTime() - started) / 1e9;
			double pending = gauge("ledgercore.outbox.pending.events");
			double failed = gauge("ledgercore.outbox.failed.events");
			if (pendingAtStop < 0) {
				pendingAtStop = pending;
			}
			samples.add(String.format(Locale.ROOT, "{\"secondsAfterLoad\":%.1f,\"pending\":%.0f,\"failed\":%.0f}", seconds,
					pending, failed));
			System.out.printf(Locale.ROOT, "OUTBOX t=%6.1fs pending=%.0f failed=%.0f%n", seconds, pending, failed);
			if (pending == 0) {
				drainSeconds = seconds;
				break;
			}
			if (seconds > 900) {
				break;
			}
			Thread.sleep(2000);
		}
		// Rate over the sampled window, whether or not the backlog fully drained within it.
		double windowSeconds = (System.nanoTime() - started) / 1e9;
		double pendingAtEnd = gauge("ledgercore.outbox.pending.events");
		double rate = windowSeconds > 0 ? (pendingAtStop - pendingAtEnd) / windowSeconds : 0;
		boolean drained = drainSeconds >= 0;
		System.out.printf(Locale.ROOT, "OUTBOX backlog at stop=%.0f, %s, about %.1f events/s%n", pendingAtStop,
				drained ? String.format(Locale.ROOT, "drained in %.1f s", drainSeconds)
						: String.format(Locale.ROOT, "%.0f still pending after %.0f s", pendingAtEnd, windowSeconds),
				rate);
		return String.format(Locale.ROOT,
				"{\"pendingBeforeLoad\":%.0f,\"pendingWhenLoadStopped\":%.0f,\"drainedWithinWindow\":%s,"
						+ "\"windowSeconds\":%.1f,\"pendingAtWindowEnd\":%.0f,\"averageDrainRatePerSecond\":%.2f,"
						+ "\"samples\":[%s]}",
				backlogBeforeLoad, pendingAtStop, drained, windowSeconds, pendingAtEnd, rate,
				String.join(",", samples));
	}

	private void writeResults(String prediction, List<String> summaries, String outbox) throws IOException {
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
		Path directory = Path.of("benchmarks", "results");
		Files.createDirectories(directory);
		Path csvFile = directory.resolve("http-load-" + stamp + ".csv");
		Path jsonFile = directory.resolve("http-load-" + stamp + ".json");
		Files.writeString(csvFile, csv.toString());
		List<String> runs = new ArrayList<>();
		for (String summary : summaries) {
			if (summary != null) {
				runs.add(summary);
			}
		}
		String environment = String.format(Locale.ROOT,
				"{\"timestampUtc\":\"%s\",\"clientJava\":\"%s\",\"os\":\"%s %s %s\",\"hostLogicalProcessors\":%d,"
						+ "\"setup\":\"client, application and PostgreSQL 17.11 (Docker Compose in the Docker Desktop VM) "
						+ "on one host\",\"passwordEncoder\":\"BCrypt cost 10 (.env.example)\","
						+ "\"connections\":\"HTTP/1.1, keep-alive connections reused by java.net.http.HttpClient\","
						+ "\"serverConnectionPool\":10,\"serverThreads\":\"Tomcat default (200)\"}",
				Instant.now(), System.getProperty("java.version"), System.getProperty("os.name"),
				System.getProperty("os.version"), System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors());
		String json = "{\n\"environment\":" + environment + ",\n\"prediction\":\"" + prediction.replace("\"", "'")
				+ "\",\n\"method\":{\"warmupOperations\":" + WARMUP_OPERATIONS + ",\"repetitions\":" + REPETITIONS
				+ ",\"operationsPerRun\":\"max(100, 50 x concurrency)\",\"accountPairs\":" + ACCOUNT_PAIRS
				+ ",\"transferAmountMinor\":1,\"retries\":\"none; non-2xx responses are counted\"},\n\"runs\":[\n"
				+ String.join(",\n", runs) + "\n],\n\"outbox\":" + outbox + "\n}\n";
		Files.writeString(jsonFile, json);
		System.out.println("Raw results: " + csvFile + " and " + jsonFile);
	}

	private String createAccount(String ownerId, String label) throws Exception {
		HttpResponse<String> response = http.send(post("/v1/accounts", ADMIN, "load-acct-" + runId + "-" + label,
				"{\"ownerId\":\"" + ownerId + "\"}"), HttpResponse.BodyHandlers.ofString());
		Matcher matcher = ACCOUNT_ID.matcher(response.body());
		if (response.statusCode() != 201 || !matcher.find()) {
			throw new IllegalStateException("Account creation failed: " + response.statusCode() + " " + response.body());
		}
		return matcher.group(1);
	}

	private void fund(String accountId, int index) throws Exception {
		HttpResponse<String> response = http.send(post("/v1/demo-funding", ADMIN, "load-fund-" + runId + "-" + index,
				"{\"destinationAccountId\":\"" + accountId
						+ "\",\"amountMinor\":\"1000000000\",\"currency\":\"INR\",\"reason\":\"http load test\"}"),
				HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 201) {
			throw new IllegalStateException("Funding failed: " + response.statusCode() + " " + response.body());
		}
	}

	private double gauge(String name) throws Exception {
		HttpResponse<String> response = http.send(get("/actuator/metrics/" + name, ADMIN),
				HttpResponse.BodyHandlers.ofString());
		Matcher matcher = GAUGE_VALUE.matcher(response.body());
		return matcher.find() ? Double.parseDouble(matcher.group(1)) : -1;
	}

	private HttpRequest get(String path, String authorization) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", authorization).GET().build();
	}

	private HttpRequest post(String path, String authorization, String key, String body) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("Authorization", authorization)
			.header("Content-Type", "application/json")
			.header("Idempotency-Key", key)
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
	}

	private static double quantile(double[] sorted, double q) {
		int index = (int) Math.ceil(q * sorted.length) - 1;
		return sorted[Math.max(0, index)];
	}

	private static String basic(String username, String password) {
		return "Basic " + Base64.getEncoder()
			.encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

}
