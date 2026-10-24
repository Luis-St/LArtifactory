package net.luis.artifactory;

import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.config.SizeUnit;
import io.javalin.http.HandlerType;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson3;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import net.luis.artifactory.auth.AuthService;
import net.luis.artifactory.auth.UserService;
import net.luis.artifactory.config.*;
import net.luis.artifactory.database.DatabaseProvider;
import net.luis.artifactory.database.SqlErrors;
import net.luis.artifactory.dto.response.ErrorResponse;
import net.luis.artifactory.handler.HealthHandler;
import net.luis.artifactory.handler.admin.RepositoryAdminHandler;
import net.luis.artifactory.handler.admin.UserAdminHandler;
import net.luis.artifactory.handler.format.RepositoryDispatcher;
import net.luis.artifactory.http.*;
import net.luis.artifactory.repository.*;
import net.luis.artifactory.storage.BlobStore;
import net.luis.artifactory.util.Json;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.audit.SqlAuditUserProvider;
import net.luis.utils.io.database.exception.SqlException;
import org.eclipse.jetty.http.UriCompliance;
import org.jspecify.annotations.NonNull;
import org.slf4j.*;
import tools.jackson.core.JacksonException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class Application {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);
	private static final List<HandlerType> REPOSITORY_METHODS = List.of(HandlerType.GET, HandlerType.HEAD, HandlerType.PUT, HandlerType.POST, HandlerType.DELETE, HandlerType.PATCH);
	/**
	 * Unreferenced blobs younger than this are kept, an upload stores its blob before the file row is committed.<br>
	 */
	private static final Duration ORPHAN_GRACE_PERIOD = Duration.ofHours(1);
	private static final Duration TEMPORARY_FILE_MAX_AGE = Duration.ofHours(24);
	private static final String UI_CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
		"connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
	
	static void main() throws SqlException {
		Env env = Env.ofSystem();
		Server server = start(ServerConfig.from(env), DatabaseConfig.from(env));
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOGGER.info("Shutting down LArtifactory");
			server.stop();
			LOGGER.info("LArtifactory stopped");
		}));
	}
	
	/**
	 * Initializes the database and storage and starts the http server.<br>
	 */
	public static @NonNull Server start(@NonNull ServerConfig serverConfig, @NonNull DatabaseConfig databaseConfig) throws SqlException {
		DatabaseProvider databaseProvider = new DatabaseProvider(databaseConfig, SqlAuditUserProvider.empty());
		databaseProvider.initialize();
		SqlDatabase database = databaseProvider.getDatabase();
		
		BlobStore blobStore = new BlobStore(serverConfig.storagePath(), serverConfig.maxUploadSize(), ORPHAN_GRACE_PERIOD);
		UserService userService = new UserService(database);
		userService.bootstrapAdmin(serverConfig.adminUsername(), serverConfig.adminPassword());
		ArtifactService artifactService = new ArtifactService(database, blobStore);
		Services services = new Services(
			serverConfig,
			userService,
			new AuthService(userService, serverConfig),
			new RepositoryService(database, artifactService),
			artifactService,
			new PackageService(database),
			new BodyReader(Math.min(serverConfig.maxBufferedUploadSize(), serverConfig.maxUploadSize() * 2), Runtime.getRuntime().maxMemory())
		);
		
		Javalin app = createApp(services, databaseProvider).start(serverConfig.port());
		LOGGER.info("LArtifactory started on port {}", app.port());
		
		ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("maintenance").daemon().factory());
		maintenance.scheduleWithFixedDelay(() -> runMaintenance(services), 1, serverConfig.maintenanceIntervalMinutes(), TimeUnit.MINUTES);
		return new Server(app, databaseProvider, services, maintenance);
	}
	
	/**
	 * Removes orphaned blobs, stale temporary files and expired sessions.<br>
	 */
	static void runMaintenance(@NonNull Services services) {
		try {
			int blobs = services.artifacts().sweepOrphanedBlobs();
			int temporary = services.artifacts().blobStore().cleanTemporaryFiles(TEMPORARY_FILE_MAX_AGE);
			int sessions = services.users().deleteExpiredSessions();
			services.auth().cleanup();
			if (blobs > 0 || temporary > 0 || sessions > 0) {
				LOGGER.info("Maintenance removed {} orphaned blobs, {} temporary files and {} expired sessions", blobs, temporary, sessions);
			}
		} catch (Exception e) {
			LOGGER.error("Maintenance failed", e);
		}
	}
	
	private static @NonNull Javalin createApp(@NonNull Services services, @NonNull DatabaseProvider databaseProvider) {
		ServerConfig serverConfig = services.config();
		var healthHandler = new HealthHandler(databaseProvider);
		var repositoryAdminHandler = new RepositoryAdminHandler(services);
		var userAdminHandler = new UserAdminHandler(services);
		var dispatcher = new RepositoryDispatcher(services);
		
		return Javalin.create(config -> {
			config.startup.showJavalinBanner = false;
			config.http.defaultContentType = "application/json";
			config.http.compressionStrategy = CompressionStrategy.NONE;
			// Only applies to bodies read as a whole (json requests), uploads are streamed or read through the BodyReader with their own limits
			config.http.maxRequestSize = BodyReader.MAX_JSON_BODY;
			config.jsonMapper(new JavalinJackson3(Json.MAPPER, false));
			
			// Package managers send encoded slashes (npm: @scope%2fname), which jetty rejects by default
			config.jetty.modifyHttpConfiguration(httpConfig -> httpConfig.setUriCompliance(UriCompliance.LEGACY));
			config.jetty.modifyServletContextHandler(handler -> handler.getServletHandler().setDecodeAmbiguousURIs(true));
			config.jetty.multipartConfig.maxFileSize(serverConfig.maxUploadSize(), SizeUnit.BYTES);
			config.jetty.multipartConfig.maxTotalRequestSize(serverConfig.maxUploadSize() * 2, SizeUnit.BYTES);
			config.jetty.multipartConfig.maxInMemoryFileSize(1, SizeUnit.MB);
			config.jetty.multipartConfig.cacheDirectory(serverConfig.storagePath().resolve("tmp").toString());
			
			// The OpenAPI spec and Swagger UI are served without authentication, keep them disabled unless explicitly enabled.
			if (serverConfig.apiDocsEnabled()) {
				config.registerPlugin(new OpenApiPlugin(pluginConfig ->
					pluginConfig.withDefinitionConfiguration((_, definition) ->
						definition.info(info ->
							info.title("LArtifactory").version("1.0.0").description("Artifact repository management API")
						)
					)
				));
				config.registerPlugin(new SwaggerPlugin());
			}
			
			// Web ui (built from ui/ into the classpath), client side routing uses the url hash
			config.staticFiles.add(staticFiles -> {
				staticFiles.hostedPath = "/ui";
				staticFiles.directory = "/ui";
				staticFiles.location = Location.CLASSPATH;
				staticFiles.headers.put("Content-Security-Policy", UI_CONTENT_SECURITY_POLICY);
				staticFiles.headers.put("X-Frame-Options", "DENY");
				staticFiles.headers.put("Referrer-Policy", "no-referrer");
				staticFiles.headers.put("X-Content-Type-Options", "nosniff");
			});
			config.spaRoot.addFile("/ui", "/ui/index.html", Location.CLASSPATH);
			
			config.routes.before(ctx -> {
				String traceId = UUID.randomUUID().toString();
				MDC.put("trace_id", traceId);
				MDC.put("source_ip", Requests.clientIp(ctx, serverConfig));
				ctx.attribute("trace_id", traceId);
				ctx.attribute("request_start", System.nanoTime());
				// Prevent browsers from MIME sniffing uploaded artifacts (e.g. a stored .html/.svg) into active content
				ctx.header("X-Content-Type-Options", "nosniff");
				ctx.header("Referrer-Policy", "no-referrer");
				if (ctx.path().equals("/ui") || ctx.path().startsWith("/ui/")) {
					ctx.header("Content-Security-Policy", UI_CONTENT_SECURITY_POLICY);
					ctx.header("X-Frame-Options", "DENY");
				}
			});
			
			config.routes.before(dispatcher::handleMappedHost);
			
			config.routes.after(ctx -> {
				Long start = ctx.attribute("request_start");
				if (start != null) {
					MDC.put("duration_ms", String.valueOf((System.nanoTime() - start) / 1_000_000));
				}
				
				LOGGER.info("{} {} {}", ctx.method(), Requests.sanitizePath(ctx.path()), ctx.status());
				MDC.clear();
			});
			
			config.routes.exception(HttpError.class, (e, ctx) -> {
				ctx.status(e.status());
				// The web ui handles authentication itself, a challenge would open the login dialog of the browser
				boolean ui = "XMLHttpRequest".equals(ctx.header("X-Requested-With"));
				e.headers().forEach((name, value) -> {
					if (!ui || !"WWW-Authenticate".equalsIgnoreCase(name)) {
						ctx.header(name, value);
					}
				});
				if (e.body() != null && !"HEAD".equalsIgnoreCase(ctx.req().getMethod())) {
					ctx.contentType(e.contentType() == null ? "text/plain" : e.contentType()).result(e.body());
				} else {
					ctx.result("");
				}
			});
			config.routes.exception(JacksonException.class, (e, ctx) -> {
				ctx.status(400).json(new ErrorResponse("Invalid json", e.getOriginalMessage()));
			});
			config.routes.exception(Exception.class, (e, ctx) -> {
				// A concurrent request created the same row (e.g. the same package version) first
				if (SqlErrors.isUniqueViolation(e)) {
					ctx.status(409).json(new ErrorResponse("Conflict", "The resource was created concurrently, retry the request"));
					return;
				}
				// A request supplied value (path, package name, version) exceeds the length of its column
				if (SqlErrors.isValueTooLong(e)) {
					ctx.status(400).json(new ErrorResponse("Bad request", "A value of the request is too long"));
					return;
				}
				LOGGER.error("Unhandled exception on {} {}", ctx.method(), Requests.sanitizePath(ctx.path()), e);
				ctx.status(500).json(new ErrorResponse("Internal server error"));
			});
			
			// Health
			config.routes.get("/health", healthHandler::health);
			
			// Web ui
			config.routes.get("/", ctx -> ctx.redirect("/ui/"));
			config.routes.get("/ui", ctx -> ctx.redirect("/ui/"));
			
			// Management api
			config.routes.get("/api/me", userAdminHandler::me);
			config.routes.post("/api/session", userAdminHandler::createSession);
			config.routes.delete("/api/session", userAdminHandler::deleteSession);
			config.routes.get("/api/repositories", repositoryAdminHandler::list);
			config.routes.post("/api/repositories", repositoryAdminHandler::create);
			config.routes.get("/api/repositories/{name}", repositoryAdminHandler::get);
			config.routes.patch("/api/repositories/{name}", repositoryAdminHandler::update);
			config.routes.delete("/api/repositories/{name}", repositoryAdminHandler::delete);
			config.routes.get("/api/repositories/{name}/packages", repositoryAdminHandler::packages);
			config.routes.delete("/api/repositories/{name}/packages", repositoryAdminHandler::deletePackage);
			config.routes.get("/api/repositories/{name}/files", repositoryAdminHandler::files);
			config.routes.get("/api/users", userAdminHandler::listUsers);
			config.routes.post("/api/users", userAdminHandler::createUser);
			config.routes.patch("/api/users/{username}", userAdminHandler::updateUser);
			config.routes.delete("/api/users/{username}", userAdminHandler::deleteUser);
			config.routes.get("/api/permissions", userAdminHandler::listPermissions);
			config.routes.put("/api/permissions", userAdminHandler::setPermission);
			config.routes.delete("/api/permissions", userAdminHandler::deletePermission);
			config.routes.get("/api/tokens", userAdminHandler::listTokens);
			config.routes.post("/api/tokens", userAdminHandler::createToken);
			config.routes.delete("/api/tokens/{id}", userAdminHandler::deleteToken);
			
			// Repositories: /{type}/{repository}/...
			for (RepositoryType type : RepositoryType.values()) {
				for (HandlerType method : REPOSITORY_METHODS) {
					config.routes.addHttpHandler(method, "/" + type.id() + "/{repository}", dispatcher::handle);
					config.routes.addHttpHandler(method, "/" + type.id() + "/{repository}/<path>", dispatcher::handle);
				}
			}
		});
	}
	
	/**
	 * A running server with its resources.<br>
	 */
	public record Server(@NonNull Javalin app, @NonNull DatabaseProvider databaseProvider, @NonNull Services services, @NonNull ScheduledExecutorService maintenance) {
		
		public int port() {
			return this.app.port();
		}
		
		public void stop() {
			this.maintenance.shutdownNow();
			this.app.stop();
			try {
				this.databaseProvider.close();
			} catch (SqlException e) {
				LOGGER.error("Failed to close database provider", e);
			}
		}
	}
}
