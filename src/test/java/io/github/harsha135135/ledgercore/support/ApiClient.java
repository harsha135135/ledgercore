package io.github.harsha135135.ledgercore.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A small real-HTTP client for contract and security tests. */
public final class ApiClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final String baseUrl;

	public ApiClient(int port) {
		this.baseUrl = "http://127.0.0.1:" + port;
	}

	public ApiResponse get(String path, String username) {
		return send(request(path, username).GET());
	}

	public ApiResponse getAnonymous(String path) {
		return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
	}

	/** Posts with a fresh Idempotency-Key, as a well-behaved client would for a new request. */
	public ApiResponse post(String path, String username, String jsonBody) {
		return post(path, username, jsonBody, Map.of("Idempotency-Key", "http-" + java.util.UUID.randomUUID()));
	}

	public ApiResponse postWithKey(String path, String username, String jsonBody, String idempotencyKey) {
		return post(path, username, jsonBody, Map.of("Idempotency-Key", idempotencyKey));
	}

	public ApiResponse post(String path, String username, String jsonBody, Map<String, String> headers) {
		HttpRequest.Builder builder = request(path, username)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
		for (Map.Entry<String, String> header : headers.entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}
		return send(builder);
	}

	public ApiResponse postWithPassword(String path, String username, String password, String jsonBody) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("Authorization", basic(username, password))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
		return send(builder);
	}

	private HttpRequest.Builder request(String path, String username) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path))
			.header("Authorization", basic(username, TestUsers.password(username)));
	}

	private static String basic(String username, String password) {
		String token = username + ":" + password;
		return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
	}

	private static ApiResponse send(HttpRequest.Builder builder) {
		try {
			HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			String body = response.body();
			JsonNode json = body == null || body.isBlank() ? null : JSON.readTree(body);
			return new ApiResponse(response.statusCode(), json, body, response.headers());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	public static String json(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return JSON.writeValueAsString(map);
	}

	public record ApiResponse(int status, JsonNode body, String rawBody, HttpHeaders headers) {

		public String text(String field) {
			JsonNode node = body == null ? null : body.get(field);
			return node == null || node.isNull() ? null : node.asString();
		}

		public String header(String name) {
			return headers.firstValue(name).orElse(null);
		}

	}

}
