package net.luis.artifactory;

import net.luis.artifactory.config.*;
import net.luis.artifactory.util.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
	
	@TempDir
	static Path storage;
	
	private final HttpClient client = HttpClient.newHttpClient();
	private Application.Server server;
	private String base;
	
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
	
	private static String sha256(byte[] data) throws Exception {
		return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
	}
	
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
		for (String repository : List.of("{\"name\":\"files\",\"type\":\"generic\"}", "{\"name\":\"maven\",\"type\":\"maven\"}", "{\"name\":\"npm\",\"type\":\"npm\"}", "{\"name\":\"cargo\",\"type\":\"cargo\"}", "{\"name\":\"oci\",\"type\":\"oci\"}")) {
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
		JsonNode packages = Json.parse(this.send("GET", "/api/repositories/maven/packages?q=net.example:lib", basic("admin", ADMIN_PASSWORD), null).body());
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
		assertTrue(statuses.stream().allMatch(status -> status == 201 || status == 403), statuses.toString());
		
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
	
	@Test
	void packageContentCanNotRunScript() throws Exception {
		assertEquals(201, this.send("PUT", "/generic/files/sandboxed/page.html", basic("admin", ADMIN_PASSWORD), "<script>alert(1)</script>").statusCode());
		HttpResponse<String> response = this.send("GET", "/generic/files/sandboxed/page.html", basic("admin", ADMIN_PASSWORD), null);
		assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("sandbox"), response.headers().toString());
	}
	
	@Test
	void concurrentUploadsToAnImmutablePathConflict() throws Exception {
		List<Callable<Integer>> uploads = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String content = "content " + i;
			uploads.add(() -> this.send("PUT", "/generic/files/immutable/race.txt", basic("admin", ADMIN_PASSWORD), content).statusCode());
		}
		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService executor = Executors.newFixedThreadPool(uploads.size())) {
			for (Future<Integer> result : executor.invokeAll(uploads)) {
				statuses.add(result.get());
			}
		}
		assertEquals(1, statuses.stream().filter(status -> status == 201).count(), statuses.toString());
		assertTrue(statuses.stream().allMatch(status -> status == 201 || status == 409), statuses.toString());
		
		String stored = this.send("GET", "/generic/files/immutable/race.txt", basic("admin", ADMIN_PASSWORD), null).body();
		assertEquals(201, this.send("PUT", "/generic/files/immutable/race.txt", basic("admin", ADMIN_PASSWORD), stored).statusCode(), "an identical upload is accepted");
	}
	
	@Test
	void ownPasswordChangeRequiresCurrentPassword() throws Exception {
		this.send("POST", "/api/users", basic("admin", ADMIN_PASSWORD), "{\"username\":\"changer\",\"password\":\"changer-password-1\"}");
		String session = "Bearer " + Json.parse(this.send("POST", "/api/session", basic("changer", "changer-password-1"), "{}").body()).get("token").asString();
		
		assertEquals(403, this.send("PATCH", "/api/users/changer", session, "{\"password\":\"changer-password-2\"}").statusCode());
		assertEquals(403, this.send("PATCH", "/api/users/changer", session, "{\"password\":\"changer-password-2\",\"currentPassword\":\"wrong-password-1\"}").statusCode());
		assertEquals(200, this.send("PATCH", "/api/users/changer", session, "{\"password\":\"changer-password-2\",\"currentPassword\":\"changer-password-1\"}").statusCode());
		assertEquals(200, this.send("PATCH", "/api/users/changer", basic("admin", ADMIN_PASSWORD), "{\"password\":\"changer-password-3\"}").statusCode(), "an admin resets the password of another user");
	}
	
	@Test
	void directoryListingContainsDirectChildren() throws Exception {
		for (String path : List.of("listing/a.txt", "listing/sub/b.txt", "listing/sub/deeper/c.txt", "listing/other/d.txt")) {
			assertEquals(201, this.send("PUT", "/generic/files/" + path, basic("admin", ADMIN_PASSWORD), path).statusCode());
		}
		String html = this.send("GET", "/generic/files/listing/", basic("admin", ADMIN_PASSWORD), null).body();
		assertTrue(html.contains(">a.txt<") && html.contains(">sub/<") && html.contains(">other/<"), html);
		assertFalse(html.contains("b.txt") || html.contains("deeper"), html);
		assertEquals(404, this.send("GET", "/generic/files/listing/missing/", basic("admin", ADMIN_PASSWORD), null).statusCode());
	}
	
	@Test
	void invalidPathsAreRejected() throws Exception {
		assertEquals(400, this.send("PUT", "/generic/files/a%2F..%2Fb.txt", basic("admin", ADMIN_PASSWORD), "x").statusCode(), "an encoded slash can not hide a '..' segment");
		assertEquals(400, this.send("PUT", "/generic/files/" + "x".repeat(1100), basic("admin", ADMIN_PASSWORD), "x").statusCode(), "a path longer than the column is a bad request");
	}
	
	@Test
	void npmTagsOnlyPointToExistingVersions() throws Exception {
		String body = npmPublishBody("tagged", "1.0.0").replace("\"dist-tags\":{\"latest\":\"1.0.0\"}", "\"dist-tags\":{\"latest\":\"1.0.0\",\"beta\":\"9.9.9\"}");
		assertEquals(201, this.send("PUT", "/npm/npm/tagged", basic("admin", ADMIN_PASSWORD), body).statusCode());
		JsonNode tags = Json.parse(this.send("GET", "/npm/npm/tagged", basic("admin", ADMIN_PASSWORD), null).body()).get("dist-tags");
		assertEquals("1.0.0", tags.get("latest").asString());
		assertFalse(tags.has("beta"), tags.toString());
	}
	
	@Test
	void npmSearchFindsPackagesByNameAndDescription() throws Exception {
		assertEquals(201, this.send("PUT", "/npm/npm/findable-one", basic("admin", ADMIN_PASSWORD), npmPublishBody("findable-one", "1.0.0")).statusCode());
		String described = npmPublishBody("other-two", "1.0.0").replace("\"version\":\"1.0.0\"}", "\"version\":\"1.0.0\",\"description\":\"also findable\"}");
		assertEquals(201, this.send("PUT", "/npm/npm/other-two", basic("admin", ADMIN_PASSWORD), described).statusCode());
		JsonNode result = Json.parse(this.send("GET", "/npm/npm/-/v1/search?text=findable&size=1", basic("admin", ADMIN_PASSWORD), null).body());
		assertEquals(2, result.get("total").asInt(), result.toString());
		assertEquals(1, result.get("objects").size());
	}
	
	@Test
	void mavenMetadataListsEquivalentVersions() throws Exception {
		assertEquals(201, this.send("PUT", "/maven/maven/net/example/equal/1.0/equal-1.0.jar", basic("admin", ADMIN_PASSWORD), "a").statusCode());
		assertEquals(201, this.send("PUT", "/maven/maven/net/example/equal/1.0.0/equal-1.0.0.jar", basic("admin", ADMIN_PASSWORD), "b").statusCode());
		String metadata = this.send("GET", "/maven/maven/net/example/equal/maven-metadata.xml", basic("admin", ADMIN_PASSWORD), null).body();
		assertTrue(metadata.contains("<version>1.0</version>") && metadata.contains("<version>1.0.0</version>"), metadata);
	}
	
	@Test
	void cargoCrateNamesTreatDashAndUnderscoreAsEqual() throws Exception {
		assertEquals(200, this.cargoPublish("dash-crate").statusCode());
		HttpResponse<String> conflict = this.cargoPublish("dash_crate");
		assertEquals(400, conflict.statusCode(), conflict.body());
	}
	
	private HttpResponse<String> cargoPublish(String name) throws IOException, InterruptedException {
		byte[] json = ("{\"name\":\"" + name + "\",\"vers\":\"0.1.0\",\"deps\":[],\"features\":{}}").getBytes(StandardCharsets.UTF_8);
		byte[] crate = new byte[16];
		ByteBuffer body = ByteBuffer.allocate(8 + json.length + crate.length).order(ByteOrder.LITTLE_ENDIAN);
		body.putInt(json.length).put(json).putInt(crate.length).put(crate);
		return this.client.send(HttpRequest.newBuilder(URI.create(this.base + "/cargo/cargo/api/v1/crates/new"))
			.header("Authorization", basic("admin", ADMIN_PASSWORD))
			.PUT(HttpRequest.BodyPublishers.ofByteArray(body.array())).build(), HttpResponse.BodyHandlers.ofString());
	}
	
	@Test
	void ociPushAndPullImage() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		HttpResponse<String> ping = this.send("GET", "/v2/", null, null);
		assertEquals(401, ping.statusCode());
		assertEquals("registry/2.0", ping.headers().firstValue("Docker-Distribution-API-Version").orElse(null));
		assertEquals(200, this.send("GET", "/v2/", admin, null).statusCode());
		
		// Chunked upload of a layer: POST, PATCH, PUT with digest
		byte[] layer = "layer-content".getBytes(StandardCharsets.UTF_8);
		String layerDigest = sha256(layer);
		HttpResponse<String> start = this.send("POST", "/v2/oci/team/app/blobs/uploads/", admin, null);
		assertEquals(202, start.statusCode(), start.body());
		String location = start.headers().firstValue("Location").orElseThrow();
		HttpResponse<String> patch = this.sendBytes("PATCH", URI.create(location), admin, null, layer);
		assertEquals(202, patch.statusCode(), patch.body());
		assertEquals("0-" + (layer.length - 1), patch.headers().firstValue("Range").orElse(null));
		HttpResponse<String> wrong = this.sendBytes("PUT", URI.create(location + "?digest=" + sha256(new byte[1])), admin, null, new byte[0]);
		assertEquals(400, wrong.statusCode(), "the digest must match the content");
		
		start = this.send("POST", "/v2/oci/team/app/blobs/uploads/", admin, null);
		location = start.headers().firstValue("Location").orElseThrow();
		assertEquals(202, this.sendBytes("PATCH", URI.create(location), admin, null, layer).statusCode());
		HttpResponse<String> put = this.sendBytes("PUT", URI.create(location + "?digest=" + layerDigest), admin, null, new byte[0]);
		assertEquals(201, put.statusCode(), put.body());
		assertEquals(layerDigest, put.headers().firstValue("Docker-Content-Digest").orElse(null));
		
		// Monolithic upload of the config
		byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
		String configDigest = sha256(config);
		assertEquals(201, this.sendBytes("POST", URI.create(this.base + "/v2/oci/team/app/blobs/uploads/?digest=" + configDigest), admin, null, config).statusCode());
		assertEquals(200, this.send("HEAD", "/v2/oci/team/app/blobs/" + configDigest, admin, null).statusCode());
		assertEquals(404, this.send("HEAD", "/v2/oci/team/app/blobs/" + sha256(new byte[2]), admin, null).statusCode());
		
		String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\"," +
			"\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + configDigest + "\",\"size\":" + config.length + "}," +
			"\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\",\"digest\":\"" + layerDigest + "\",\"size\":" + layer.length + "}]}";
		byte[] manifestBytes = manifest.getBytes(StandardCharsets.UTF_8);
		String manifestType = "application/vnd.oci.image.manifest.v1+json";
		HttpResponse<String> pushed = this.sendBytes("PUT", URI.create(this.base + "/v2/oci/team/app/manifests/1.0"), admin, manifestType, manifestBytes);
		assertEquals(201, pushed.statusCode(), pushed.body());
		assertEquals(sha256(manifestBytes), pushed.headers().firstValue("Docker-Content-Digest").orElse(null));
		
		String missing = manifest.replace(layerDigest, sha256(new byte[3]));
		assertEquals(400, this.sendBytes("PUT", URI.create(this.base + "/v2/oci/team/app/manifests/broken"), admin, manifestType, missing.getBytes(StandardCharsets.UTF_8)).statusCode(), "referenced blobs must exist");
		String other = manifest.replace("\"layers\"", "\"annotations\":{\"a\":\"b\"},\"layers\"");
		assertEquals(409, this.sendBytes("PUT", URI.create(this.base + "/v2/oci/team/app/manifests/1.0"), admin, manifestType, other.getBytes(StandardCharsets.UTF_8)).statusCode(), "tags are immutable without redeploy");
		
		HttpResponse<String> pulled = this.send("GET", "/v2/oci/team/app/manifests/1.0", admin, null);
		assertEquals(200, pulled.statusCode());
		assertEquals(manifest, pulled.body());
		assertEquals(manifestType, pulled.headers().firstValue("Content-Type").orElse(null));
		assertEquals("layer-content", this.send("GET", "/v2/oci/team/app/blobs/" + layerDigest, admin, null).body());
		assertEquals(manifest, this.send("GET", "/oci/oci/v2/team/app/manifests/" + sha256(manifestBytes), admin, null).body(), "the api is also served below the repository");
		
		JsonNode tags = Json.parse(this.send("GET", "/v2/oci/team/app/tags/list", admin, null).body());
		assertEquals("oci/team/app", tags.get("name").asString());
		assertEquals("1.0", tags.get("tags").get(0).asString());
		JsonNode catalog = Json.parse(this.send("GET", "/v2/_catalog", admin, null).body());
		assertTrue(catalog.get("repositories").toString().contains("oci/team/app"), catalog.toString());
		
		assertEquals(202, this.send("DELETE", "/v2/oci/team/app/manifests/" + sha256(manifestBytes), admin, null).statusCode());
		assertEquals(404, this.send("GET", "/v2/oci/team/app/manifests/1.0", admin, null).statusCode(), "deleting a manifest removes its tags");
	}
	
	@Test
	void ociTokenFlowAllowsAnonymousPullFromPublicRepositories() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"oci-public\",\"type\":\"oci\",\"publicRead\":true}").statusCode());
		byte[] config = "{}".getBytes(StandardCharsets.UTF_8);
		String configDigest = sha256(config);
		assertEquals(201, this.sendBytes("POST", URI.create(this.base + "/v2/oci-public/app/blobs/uploads/?digest=" + configDigest), admin, null, config).statusCode());
		String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\",\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"digest\":\"" + configDigest + "\",\"size\":2},\"layers\":[]}";
		assertEquals(201, this.sendBytes("PUT", URI.create(this.base + "/v2/oci-public/app/manifests/1.0"), admin, "application/vnd.oci.image.manifest.v1+json", manifest.getBytes(StandardCharsets.UTF_8)).statusCode());
		
		HttpResponse<String> ping = this.send("GET", "/v2/", null, null);
		assertEquals(401, ping.statusCode());
		String challenge = ping.headers().firstValue("WWW-Authenticate").orElse("");
		assertTrue(challenge.startsWith("Bearer realm=\"" + this.base + "/v2/token\""), challenge);
		assertEquals("UNAUTHORIZED", Json.parse(ping.body()).get("errors").get(0).get("code").asString());
		
		String anonymous = Json.parse(this.send("GET", "/v2/token?service=LArtifactory&scope=repository:oci-public/app:pull", null, null).body()).get("token").asString();
		assertEquals(200, this.send("GET", "/v2/oci-public/app/manifests/1.0", "Bearer " + anonymous, null).statusCode(), "anonymous tokens read public repositories");
		assertEquals(401, this.send("GET", "/v2/oci/team/app/tags/list", "Bearer " + anonymous, null).statusCode(), "anonymous tokens do not read private repositories");
		
		JsonNode token = Json.parse(this.send("GET", "/v2/token?service=LArtifactory", admin, null).body());
		assertTrue(token.get("token").asString().startsWith("las_"), "a password is exchanged for a session");
		assertTrue(token.get("expires_in").asLong() > 0);
		assertEquals(200, this.send("GET", "/v2/", "Bearer " + token.get("token").asString(), null).statusCode());
		assertEquals(401, this.send("GET", "/v2/token", basic("admin", "wrong-password"), null).statusCode());
		
		HttpResponse<String> referrers = this.send("GET", "/v2/oci-public/app/referrers/" + sha256(manifest.getBytes(StandardCharsets.UTF_8)), admin, null);
		assertEquals("application/vnd.oci.image.index.v1+json", referrers.headers().firstValue("Content-Type").orElse(null));
		HttpResponse<String> referenced = this.send("DELETE", "/v2/oci-public/app/blobs/" + configDigest, admin, null);
		assertEquals(409, referenced.statusCode(), "a referenced blob is not deleted");
		assertEquals("DENIED", Json.parse(referenced.body()).get("errors").get(0).get("code").asString());
	}
	
	@Test
	void virtualMavenRepositoryServesMergedMetadataAndChecksums() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"maven-second\",\"type\":\"maven\"}").statusCode());
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"maven-group\",\"type\":\"maven\",\"members\":[\"maven\",\"maven-second\"]}").statusCode());
		assertEquals(201, this.send("PUT", "/maven/maven/net/example/merged/1.0/merged-1.0.jar", admin, "a").statusCode());
		assertEquals(201, this.send("PUT", "/maven/maven-second/net/example/merged/2.0/merged-2.0.jar", admin, "b").statusCode());
		assertEquals(201, this.send("PUT", "/maven/maven-second/net/example/merged/3.0-SNAPSHOT/merged-3.0-20250101.120000-1.jar", admin, "c").statusCode());
		
		HttpResponse<String> metadata = this.send("GET", "/maven/maven-group/net/example/merged/maven-metadata.xml", admin, null);
		assertEquals(200, metadata.statusCode());
		assertTrue(metadata.body().contains("<version>1.0</version>") && metadata.body().contains("<version>2.0</version>"), metadata.body());
		assertTrue(metadata.body().contains("<release>2.0</release>"), metadata.body());
		assertEquals(200, this.send("GET", "/maven/maven-group/net/example/merged/maven-metadata.xml.sha1", admin, null).statusCode());
		assertEquals(200, this.send("GET", "/maven/maven-group/net/example/merged/1.0/merged-1.0.jar.sha1", admin, null).statusCode());
		assertEquals("c", this.send("GET", "/maven/maven-group/net/example/merged/3.0-SNAPSHOT/merged-3.0-SNAPSHOT.jar", admin, null).body());
	}
	
	@Test
	void genericRepositoryStoresChecksumFilesAsContent() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("PUT", "/generic/files/sums/file", admin, "content").statusCode());
		assertEquals(404, this.send("GET", "/generic/files/sums/file.md5", admin, null).statusCode(), "no sidecar is computed");
		assertEquals(201, this.send("PUT", "/generic/files/sums/file.sha256", admin, "SHA256 (file) = abc\nSHA256 (other) = def").statusCode());
		assertEquals("SHA256 (file) = abc\nSHA256 (other) = def", this.send("GET", "/generic/files/sums/file.sha256", admin, null).body());
	}
	
	@Test
	void downloadsHaveValidDatesRangesAndDirectoryRedirects() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("PUT", "/generic/files/ranges/data.txt", admin, "0123456789").statusCode());
		HttpResponse<String> download = this.sendHeaders("GET", "/generic/files/ranges/data.txt", Map.of("Authorization", admin));
		String lastModified = download.headers().firstValue("Last-Modified").orElse("");
		assertTrue(lastModified.matches("[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT"), lastModified);
		
		assertEquals("234", this.sendHeaders("GET", "/generic/files/ranges/data.txt", Map.of("Authorization", admin, "Range", "bytes=2-4")).body());
		HttpResponse<String> unsatisfiable = this.sendHeaders("GET", "/generic/files/ranges/data.txt", Map.of("Authorization", admin, "Range", "bytes=10-"));
		assertEquals(416, unsatisfiable.statusCode());
		assertEquals("bytes */10", unsatisfiable.headers().firstValue("Content-Range").orElse(null));
		
		HttpResponse<String> directory = this.sendHeaders("GET", "/generic/files/ranges", Map.of("Authorization", admin));
		assertEquals(301, directory.statusCode());
		assertEquals("ranges/", directory.headers().firstValue("Location").orElse(null));
	}
	
	@Test
	void npmRejectsInvalidVersionsAndRepublishes() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(400, this.send("PUT", "/npm/npm/bad-version", admin, npmPublishBody("bad-version", "latest")).statusCode());
		assertEquals(400, this.send("PUT", "/npm/npm/bad-version", admin, npmPublishBody("bad-version", "1.0")).statusCode());
		String otherName = npmPublishBody("bad-version", "1.0.0").replace("{\"name\":\"bad-version\",\"version\"", "{\"name\":\"other\",\"version\"");
		assertEquals(400, this.send("PUT", "/npm/npm/bad-version", admin, otherName).statusCode(), "the manifest must name the package");
		assertEquals(201, this.send("PUT", "/npm/npm/republished", admin, npmPublishBody("republished", "1.0.0")).statusCode());
		assertEquals(403, this.send("PUT", "/npm/npm/republished", admin, npmPublishBody("republished", "1.0.0")).statusCode());
		assertEquals(400, this.send("POST", "/api/repositories", admin, "{\"name\":\"npm-redeploy\",\"type\":\"npm\",\"allowRedeploy\":true}").statusCode());
	}
	
	@Test
	void virtualRepositoryOnlyServesReadableMembers() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"private-member\",\"type\":\"generic\"}").statusCode());
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"public-group\",\"type\":\"generic\",\"publicRead\":true,\"members\":[\"private-member\"]}").statusCode());
		assertEquals(201, this.send("PUT", "/generic/private-member/secret.txt", admin, "secret").statusCode());
		assertEquals(404, this.send("GET", "/generic/public-group/secret.txt", null, null).statusCode(), "a public virtual repository does not publish a private member");
		assertEquals("secret", this.send("GET", "/generic/public-group/secret.txt", admin, null).body(), "a reader of the member resolves it through the group");
	}
	
	@Test
	void virtualRepositoryStaysVirtualWithoutMembers() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"only-member\",\"type\":\"generic\"}").statusCode());
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"lonely-group\",\"type\":\"generic\",\"members\":[\"only-member\"]}").statusCode());
		assertEquals(204, this.send("DELETE", "/api/repositories/only-member", admin, null).statusCode());
		assertEquals("VIRTUAL", Json.parse(this.send("GET", "/api/repositories/lonely-group", admin, null).body()).get("kind").asString());
		assertEquals(405, this.send("PUT", "/generic/lonely-group/file.txt", admin, "x").statusCode(), "a virtual repository never accepts uploads");
	}
	
	@Test
	void rejectedRepositoryUpdateChangesNothing() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"unchanged\",\"type\":\"generic\",\"description\":\"before\"}").statusCode());
		assertEquals(400, this.send("PATCH", "/api/repositories/unchanged", admin, "{\"description\":\"after\",\"publicRead\":true,\"members\":[\"files\"]}").statusCode());
		JsonNode repository = Json.parse(this.send("GET", "/api/repositories/unchanged", admin, null).body());
		assertEquals("before", repository.get("description").asString());
		assertFalse(repository.get("publicRead").asBoolean());
	}
	
	@Test
	void remoteUpdateKeepsOmittedFields() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"upstream\",\"type\":\"generic\",\"remote\":{\"url\":\"https://example.invalid/repo\",\"cacheEnabled\":true,\"username\":\"u\",\"password\":\"p\"}}").statusCode());
		assertEquals(200, this.send("PATCH", "/api/repositories/upstream", admin, "{\"remote\":{\"url\":\"https://example.invalid/other\"}}").statusCode());
		JsonNode remote = Json.parse(this.send("GET", "/api/repositories/upstream", admin, null).body()).get("remote");
		assertEquals("https://example.invalid/other", remote.get("url").asString());
		assertTrue(remote.get("cacheEnabled").asBoolean());
		assertEquals("u", remote.get("username").asString());
		assertTrue(remote.get("hasPassword").asBoolean());
	}
	
	@Test
	void tokenSessionsAreRestricted() throws Exception {
		JsonNode token0 = Json.parse(this.send("POST", "/api/tokens", basic("admin", ADMIN_PASSWORD), "{\"name\":\"ui\",\"level\":\"WRITE\"}").body());
		String token = token0.get("token").asString();
		String id = token0.get("id").asString();
		HttpResponse<String> created = this.send("POST", "/api/session", basic("__token__", token), "{}");
		assertEquals(201, created.statusCode(), created.body());
		String session = "Bearer " + Json.parse(created.body()).get("token").asString();
		JsonNode me = Json.parse(this.send("GET", "/api/me", session, null).body());
		assertEquals("WRITE", me.get("tokenLevel").asString(), "a token session keeps the level of the token");
		assertEquals(403, this.send("GET", "/api/users", session, null).statusCode(), "a token session of an administrator does not administer");
		assertEquals(403, this.send("GET", "/api/tokens", session, null).statusCode());
		
		assertEquals(204, this.send("DELETE", "/api/tokens/" + id, basic("admin", ADMIN_PASSWORD), null).statusCode());
		assertEquals(401, this.send("GET", "/api/me", session, null).statusCode(), "revoking the token ends its sessions");
	}
	
	@Test
	void registrySessionsDoNotAdminister() throws Exception {
		String token = Json.parse(this.send("GET", "/v2/token?service=LArtifactory", basic("admin", ADMIN_PASSWORD), null).body()).get("token").asString();
		assertEquals(403, this.send("GET", "/api/users", "Bearer " + token, null).statusCode());
	}
	
	@Test
	void managementErrorsAreJson() throws Exception {
		HttpResponse<String> missing = this.send("GET", "/api/repositories/does-not-exist", basic("admin", ADMIN_PASSWORD), null);
		assertEquals(404, missing.statusCode());
		assertTrue(missing.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), missing.headers().toString());
		assertTrue(Json.parse(missing.body()).get("error").asString().contains("does-not-exist"));
		assertTrue(this.send("GET", "/generic/files/does-not-exist.txt", basic("admin", ADMIN_PASSWORD), null).headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
	}
	
	@Test
	void usernamesDifferingOnlyByCaseAreRejected() throws Exception {
		assertEquals(409, this.send("POST", "/api/users", basic("admin", ADMIN_PASSWORD), "{\"username\":\"Dev\",\"password\":\"developer-pass-1\"}").statusCode());
	}
	
	@Test
	void tokensDefaultToRead() throws Exception {
		HttpResponse<String> response = this.send("POST", "/api/tokens", basic("dev", "developer-pass-1"), "{\"name\":\"default\"}");
		assertEquals("READ", Json.parse(response.body()).get("level").asString());
	}
	
	@Test
	void browseEndpointsAggregateOnTheServer() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		assertEquals(201, this.send("POST", "/api/repositories", admin, "{\"name\":\"browse\",\"type\":\"generic\"}").statusCode());
		assertEquals(201, this.send("PUT", "/generic/browse/a/one.txt", admin, "1").statusCode());
		assertEquals(201, this.send("PUT", "/generic/browse/a/b/two.txt", admin, "22").statusCode());
		assertEquals(201, this.send("PUT", "/npm/npm/browsed", admin, npmPublishBody("browsed", "1.0.0")).statusCode());
		
		JsonNode summary = Json.parse(this.send("GET", "/api/summary?repository=browse", admin, null).body());
		JsonNode repository = summary.get("repositories").get(0);
		assertEquals(2, repository.get("files").asLong());
		assertEquals(3, repository.get("size").asLong());
		JsonNode all = Json.parse(this.send("GET", "/api/summary", admin, null).body());
		assertFalse(all.get("activity").isEmpty());
		assertFalse(all.get("recent").isEmpty());
		
		HttpResponse<String> packages = this.send("GET", "/api/packages?type=npm&q=browsed", admin, null);
		assertEquals("1", packages.headers().firstValue("X-Total-Count").orElse(null), packages.body());
		assertEquals("browsed", Json.parse(packages.body()).get(0).get("name").asString());
		
		HttpResponse<String> files = this.send("GET", "/api/files?repository=browse&sort=size&limit=1", admin, null);
		assertEquals("2", files.headers().firstValue("X-Total-Count").orElse(null));
		assertEquals("a/b/two.txt", Json.parse(files.body()).get(0).get("path").asString());
		
		JsonNode tree = Json.parse(this.send("GET", "/api/repositories/browse/tree?prefix=a/", admin, null).body());
		assertEquals("b", tree.get("folders").get(0).asString());
		assertEquals("a/one.txt", tree.get("files").get(0).get("path").asString());
		
		JsonNode pkg = Json.parse(this.send("GET", "/api/repositories/npm/packages/browsed", admin, null).body());
		assertEquals(1, pkg.get("files").size());
		assertEquals("browsed", Json.parse(this.send("GET", "/api/search?q=rowse", admin, null).body()).get(0).get("name").asString());
		assertEquals(0, Json.parse(this.send("GET", "/api/search?q=browsed", null, null).body()).size(), "anonymous callers only search public repositories");
	}
	
	@Test
	void auditIsPagedInTheDatabase() throws Exception {
		String admin = basic("admin", ADMIN_PASSWORD);
		HttpResponse<String> first = this.send("GET", "/api/audit?limit=2", admin, null);
		long total = Long.parseLong(first.headers().firstValue("X-Total-Count").orElseThrow());
		assertTrue(total > 2, first.body());
		JsonNode page = Json.parse(first.body());
		JsonNode second = Json.parse(this.send("GET", "/api/audit?limit=2&offset=2", admin, null).body());
		assertTrue(page.get(0).get("timestamp").asString().compareTo(page.get(1).get("timestamp").asString()) >= 0, "newest first");
		assertNotEquals(page.get(1).get("id").asString(), second.get(0).get("id").asString());
		assertEquals(400, this.send("GET", "/api/audit?limit=many", admin, null).statusCode());
	}
	
	@Test
	void repositoryResponseCarriesTheAccessLevel() throws Exception {
		this.send("PUT", "/api/permissions", basic("admin", ADMIN_PASSWORD), "{\"repository\":\"files\",\"username\":\"dev\",\"level\":\"WRITE\"}");
		assertEquals("WRITE", Json.parse(this.send("GET", "/api/repositories/files", basic("dev", "developer-pass-1"), null).body()).get("access").asString());
		assertEquals("DELETE", Json.parse(this.send("GET", "/api/repositories/files", basic("admin", ADMIN_PASSWORD), null).body()).get("access").asString());
	}
	
	private HttpResponse<String> sendHeaders(String method, String path, Map<String, String> headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.base + path)).method(method, HttpRequest.BodyPublishers.noBody());
		headers.forEach(builder::header);
		return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}
	
	private HttpResponse<String> sendBytes(String method, URI uri, String authorization, String contentType, byte[] body) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.ofByteArray(body)).header("Authorization", authorization);
		if (contentType != null) {
			builder.header("Content-Type", contentType);
		}
		return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
}
