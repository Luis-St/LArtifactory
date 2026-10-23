package net.luis.artifactory;

import net.luis.artifactory.config.*;
import net.luis.artifactory.util.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Starts the server against a PostgreSQL container and checks it over http, skipped if docker is not available.<br>
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationIntegrationTest {

	private static final String ADMIN_PASSWORD = "admin-password-123";

	@Container
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

	@TempDir
	static Path storage;

	private final HttpClient client = HttpClient.newHttpClient();
	private Application.Server server;
	private String base;

	@BeforeAll
	void start() throws Exception {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		ServerConfig config = ServerConfig.from(Env.of(Map.of(
			EnvKeys.PORT, String.valueOf(port),
			EnvKeys.STORAGE_PATH, storage.toString(),
			EnvKeys.ADMIN_PASSWORD, ADMIN_PASSWORD,
			EnvKeys.LOGIN_MAX_FAILURES, "3",
			EnvKeys.MAX_BUFFERED_UPLOAD_SIZE, "4"
		)));
		this.server = Application.start(config, new DatabaseConfig(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 4));
		this.base = "http://localhost:" + this.server.port();

		this.send("POST", "/api/users", basic("admin", ADMIN_PASSWORD), "{\"username\":\"second-admin\",\"password\":\"second-password-1\",\"admin\":true}");
		this.send("POST", "/api/users", basic("admin", ADMIN_PASSWORD), "{\"username\":\"dev\",\"password\":\"developer-pass-1\"}");
		for (String repository : List.of("{\"name\":\"files\",\"type\":\"generic\"}", "{\"name\":\"maven\",\"type\":\"maven\"}", "{\"name\":\"npm\",\"type\":\"npm\"}")) {
			assertEquals(201, this.send("POST", "/api/repositories", basic("admin", ADMIN_PASSWORD), repository).statusCode());
		}
	}

	@AfterAll
	void stop() {
		if (this.server != null) {
			this.server.stop();
		}
	}

	@Test
	void healthIsUp() throws Exception {
		HttpResponse<String> response = this.send("GET", "/health", null, null);
		assertEquals(200, response.statusCode());
		assertEquals("UP", Json.parse(response.body()).get("database").asString());
	}

	@Test
	void migrationsCreateIndexesAndSessions() throws Exception {
		try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 ResultSet result = connection.createStatement().executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'")) {
			Set<String> indexes = new HashSet<>();
			while (result.next()) {
				indexes.add(result.getString(1));
			}
			assertTrue(indexes.containsAll(Set.of("idx_files_sha256", "idx_files_package_version", "idx_tokens_username", "idx_sessions_username")), indexes.toString());
		}
	}

	@Test
	void sessionReplacesStoredPassword() throws Exception {
		HttpResponse<String> created = this.send("POST", "/api/session", basic("second-admin", "second-password-1"), "{\"remember\":true}");
		assertEquals(201, created.statusCode());
		String token = Json.parse(created.body()).get("token").asString();
		assertTrue(token.startsWith("las_"));
		String bearer = "Bearer " + token;

		assertEquals(200, this.send("GET", "/api/users", bearer, null).statusCode(), "a session has the rights of the password");
		assertEquals(403, this.send("POST", "/api/session", bearer, "{}").statusCode(), "a session can not extend itself");
		assertEquals(204, this.send("DELETE", "/api/session", bearer, null).statusCode());
		assertEquals(401, this.send("GET", "/api/me", bearer, null).statusCode());
	}

	@Test
	void tokensCanNotManageTokens() throws Exception {
		String token = this.createToken(basic("dev", "developer-pass-1"));
		assertEquals(403, this.send("POST", "/api/tokens", basic("__token__", token), "{\"name\":\"forever\"}").statusCode());
		assertEquals(403, this.send("GET", "/api/tokens", "Bearer " + token, null).statusCode());
		assertEquals(200, this.send("GET", "/api/me", "Bearer " + token, null).statusCode());
	}

	@Test
	void failedLoginsAreThrottled() throws Exception {
		this.send("POST", "/api/users", basic("admin", ADMIN_PASSWORD), "{\"username\":\"victim\",\"password\":\"victim-password-1\"}");
		for (int i = 0; i < 3; i++) {
			assertEquals(401, this.send("GET", "/api/me", basic("victim", "wrong-password-" + i), null).statusCode());
		}
		HttpResponse<String> locked = this.send("GET", "/api/me", basic("victim", "victim-password-1"), null);
		assertEquals(429, locked.statusCode());
		assertTrue(locked.headers().firstValue("Retry-After").isPresent());
	}

	@Test
	void lastAdministratorCanNotBeDemoted() throws Exception {
		assertEquals(200, this.send("PATCH", "/api/users/second-admin", basic("admin", ADMIN_PASSWORD), "{\"admin\":false}").statusCode());
		assertEquals(400, this.send("PATCH", "/api/users/admin", basic("admin", ADMIN_PASSWORD), "{\"admin\":false}").statusCode());
		assertEquals(200, this.send("PATCH", "/api/users/second-admin", basic("admin", ADMIN_PASSWORD), "{\"admin\":true}").statusCode());
	}

	@Test
	void unauthenticatedBodiesAreLimited() throws Exception {
		String body = "{\"name\":\"dev\",\"password\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";
		assertEquals(413, this.send("PUT", "/npm/npm/-/user/org.couchdb.user:dev", null, body).statusCode());
		assertEquals(413, this.send("POST", "/api/users", basic("admin", ADMIN_PASSWORD), body).statusCode());
	}

	@Test
	void largeUploadsAreStreamed() throws Exception {
		byte[] data = new byte[3 * 1024 * 1024];
		new Random(1).nextBytes(data);
		HttpResponse<String> upload = this.client.send(HttpRequest.newBuilder(URI.create(this.base + "/generic/files/large.bin"))
			.header("Authorization", basic("admin", ADMIN_PASSWORD))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(201, upload.statusCode());
		HttpResponse<byte[]> download = this.client.send(HttpRequest.newBuilder(URI.create(this.base + "/generic/files/large.bin"))
			.header("Authorization", basic("admin", ADMIN_PASSWORD)).build(), HttpResponse.BodyHandlers.ofByteArray());
		assertArrayEquals(data, download.body());
	}

	@Test
	void parallelUploadsOfOneMavenVersionSucceed() throws Exception {
		List<Callable<Integer>> uploads = new ArrayList<>();
		for (String file : List.of("lib-1.0.jar", "lib-1.0.pom", "lib-1.0-sources.jar", "lib-1.0-javadoc.jar", "lib-1.0.module", "lib-1.0-tests.jar")) {
			uploads.add(() -> this.send("PUT", "/maven/maven/net/example/lib/1.0/" + file, basic("admin", ADMIN_PASSWORD), file).statusCode());
		}
		try (ExecutorService executor = Executors.newFixedThreadPool(uploads.size())) {
			for (Future<Integer> result : executor.invokeAll(uploads)) {
				assertEquals(201, result.get());
			}
		}
		JsonNode packages = Json.parse(this.send("GET", "/api/repositories/maven/packages", basic("admin", ADMIN_PASSWORD), null).body());
		assertEquals(1, packages.size());
		assertEquals(1, packages.get(0).get("versions").size());
	}

	@Test
	void concurrentNpmPublishOfOneVersionConflicts() throws Exception {
		String body = npmPublishBody("race", "1.0.0");
		List<Callable<Integer>> publishes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			publishes.add(() -> this.send("PUT", "/npm/npm/race", basic("admin", ADMIN_PASSWORD), body).statusCode());
		}
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(publishes.size())) {
			for (Future<Integer> result : executor.invokeAll(publishes)) {
				statuses.add(result.get());
			}
		}
		assertEquals(1, statuses.stream().filter(status -> status == 201).count(), statuses.toString());
		assertTrue(statuses.stream().allMatch(status -> status == 201 || status == 409), statuses.toString());

		JsonNode packument = Json.parse(this.send("GET", "/npm/npm/race", basic("admin", ADMIN_PASSWORD), null).body());
		assertEquals("1.0.0", packument.get("dist-tags").get("latest").asString());
	}

	@Test
	void npmPublishLargerThanBufferedLimitIsRejected() throws Exception {
		String body = npmPublishBody("huge", "1.0.0", 5 * 1024 * 1024);
		assertEquals(413, this.send("PUT", "/npm/npm/huge", basic("admin", ADMIN_PASSWORD), body).statusCode());
	}

	@Test
	void listsArePaged() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertEquals(201, this.send("PUT", "/generic/files/paged/file-" + i + ".txt", basic("admin", ADMIN_PASSWORD), "content " + i).statusCode());
		}
		assertEquals(201, this.send("PUT", "/generic/files/pagedXother.txt", basic("admin", ADMIN_PASSWORD), "other").statusCode());
		HttpResponse<String> page = this.send("GET", "/api/repositories/files/files?prefix=paged/&offset=1&limit=2", basic("admin", ADMIN_PASSWORD), null);
		assertEquals("5", page.headers().firstValue("X-Total-Count").orElseThrow());
		JsonNode files = Json.parse(page.body());
		assertEquals(2, files.size());
		assertEquals("paged/file-1.txt", files.get(0).get("path").asString());

		HttpResponse<String> escaped = this.send("GET", "/api/repositories/files/files?prefix=paged_", basic("admin", ADMIN_PASSWORD), null);
		assertEquals("0", escaped.headers().firstValue("X-Total-Count").orElseThrow(), "an underscore in the prefix must not act as a wildcard");
	}

	@Test
	void uiIsServedWithSecurityHeaders() throws Exception {
		HttpResponse<String> response = this.send("GET", "/ui/", null, null);
		assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"), response.headers().toString());
		assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""));
	}

	private String createToken(String authorization) throws Exception {
		HttpResponse<String> response = this.send("POST", "/api/tokens", authorization, "{\"name\":\"ci\",\"level\":\"WRITE\",\"expiresInDays\":1}");
		assertEquals(201, response.statusCode(), response.body());
		return Json.parse(response.body()).get("token").asString();
	}

	private HttpResponse<String> send(String method, String path, String authorization, String body) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.base + path))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (authorization != null) {
			builder.header("Authorization", authorization);
		}
		if (body != null) {
			builder.header("Content-Type", "application/json");
		}
		return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String npmPublishBody(String name, String version) {
		return npmPublishBody(name, version, 16);
	}

	private static String npmPublishBody(String name, String version, int tarballSize) {
		String data = Base64.getEncoder().encodeToString(new byte[tarballSize]);
		return "{\"name\":\"" + name + "\",\"versions\":{\"" + version + "\":{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}}," +
			"\"dist-tags\":{\"latest\":\"" + version + "\"},\"_attachments\":{\"" + name + "-" + version + ".tgz\":{\"data\":\"" + data + "\",\"length\":" + tarballSize + "}}}";
	}

	private static String basic(String username, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
	}
}
