package net.luis.artifactory;

import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.config.SizeUnit;
import io.javalin.http.HandlerType;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson3;
import io.javalin.openapi.BasicAuth;
import io.javalin.openapi.BearerAuth;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import net.luis.artifactory.auth.*;
import net.luis.artifactory.config.*;
import net.luis.artifactory.database.DatabaseProvider;
import net.luis.artifactory.database.SqlErrors;
import net.luis.artifactory.dto.response.ErrorResponse;
import net.luis.artifactory.handler.HealthHandler;
import net.luis.artifactory.handler.admin.*;
import net.luis.artifactory.handler.format.OciHandler;
import net.luis.artifactory.handler.format.RepositoryDispatcher;
import net.luis.artifactory.http.*;
import net.luis.artifactory.mail.MailSender;
import net.luis.artifactory.mail.SmtpMailSender;
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

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Application {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);
	private static final List<HandlerType> REPOSITORY_METHODS = List.of(HandlerType.GET, HandlerType.HEAD, HandlerType.PUT, HandlerType.POST, HandlerType.DELETE, HandlerType.PATCH);
	/**
	 * Unreferenced blobs younger than this are kept, an upload stores its blob before the file row is committed.<br>
	 */
	private static final Duration ORPHAN_GRACE_PERIOD = Duration.ofHours(1);
	private static final Duration TEMPORARY_FILE_MAX_AGE = Duration.ofHours(24);
	/**
	 * OCI blobs no manifest references are kept this long, a push uploads all blobs before its manifest.<br>
	 */
	private static final Duration OCI_BLOB_MIN_AGE = Duration.ofHours(24);
	private static final String UI_CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
		"connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
	/**
	 * Policy of all responses outside the web ui, package content (e.g. an uploaded .nuspec served as xml) must never run script in this origin.<br>
	 */
	private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'; sandbox";
	private static final List<String> API_DOCS_PREFIXES = List.of("/swagger", "/openapi", "/webjars/");
	
	static void main() throws SqlException {
		Env env = Env.ofSystem();
		MailConfig mailConfig = MailConfig.from(env);
		MailSender mail = mailConfig.enabled() ? new SmtpMailSender(mailConfig) : MailSender.disabled();
		if (!mailConfig.enabled()) {
			LOGGER.info("No smtp server configured ({}), email addresses, codes by email and password recovery are unavailable", EnvKeys.SMTP_HOST);
		}
		Server server = start(ServerConfig.from(env), DatabaseConfig.from(env), mail);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOGGER.info("Shutting down LArtifactory");
			server.stop();
			LOGGER.info("LArtifactory stopped");
		}));
	}
	
	/**
	 * Initializes the database and storage and starts the http server.<br>
	 */
	@NonNull
	public static Server start(@NonNull ServerConfig serverConfig, @NonNull DatabaseConfig databaseConfig, @NonNull MailSender mail) throws SqlException {
		DatabaseProvider databaseProvider = new DatabaseProvider(databaseConfig, SqlAuditUserProvider.empty());
		databaseProvider.initialize();
		SqlDatabase database = databaseProvider.getDatabase();
		
		BlobStore blobStore = new BlobStore(serverConfig.storagePath(), serverConfig.maxUploadSize(), ORPHAN_GRACE_PERIOD);
		Pepper pepper = Pepper.load(serverConfig.passwordPepper(), serverConfig.storagePath());
		UserService userService = new UserService(database, new PasswordHasher(pepper));
		userService.bootstrapAdmin(serverConfig.adminUsername(), serverConfig.adminPassword());
		WebhookService webhookService = new WebhookService(database);
		AuditService auditService = new AuditService(database);
		ArtifactService artifactService = new ArtifactService(database, blobStore, webhookService, auditService);
		TokenUsageService tokenUsageService = new TokenUsageService(userService);
		RepositoryService repositoryService = new RepositoryService(database, artifactService);
		int backfilled = repositoryService.backfillKinds();
		if (backfilled > 0) {
			LOGGER.info("Stored the class of {} existing repositories", backfilled);
		}
		AuthService authService = new AuthService(userService, tokenUsageService, serverConfig, auditService);
		Services services = new Services(
			serverConfig,
			userService,
			authService,
			new AccountSecurityService(userService, authService, pepper, mail, serverConfig, auditService, tokenUsageService),
			tokenUsageService,
			repositoryService,
			artifactService,
			new PackageService(database),
			new BodyReader(Math.min(serverConfig.maxBufferedUploadSize(), serverConfig.maxUploadSize() * 2), Runtime.getRuntime().maxMemory()),
			auditService,
			webhookService,
			new ProxyService(serverConfig.maxUploadSize()),
			database
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
			int ociBlobs = OciHandler.collectGarbage(services, OCI_BLOB_MIN_AGE);
			int audit = services.audit().prune(services.config().auditRetentionDays());
			services.auth().cleanup();
			services.security().cleanup();
			if (blobs > 0 || temporary > 0 || sessions > 0 || ociBlobs > 0 || audit > 0) {
				LOGGER.info("Maintenance removed {} orphaned blobs, {} temporary files, {} expired sessions, {} unreferenced OCI blobs and {} expired audit entries", blobs, temporary, sessions, ociBlobs, audit);
			}
		} catch (Exception e) {
			LOGGER.error("Maintenance failed", e);
		}
	}
	
	@NonNull
	private static Javalin createApp(@NonNull Services services, @NonNull DatabaseProvider databaseProvider) {
		ServerConfig serverConfig = services.config();
		var healthHandler = new HealthHandler(databaseProvider, serverConfig.apiDocsEnabled());
		var browseHandler = new BrowseHandler(services);
		AtomicBoolean proxyWarned = new AtomicBoolean();
		var repositoryAdminHandler = new RepositoryAdminHandler(services);
		var userAdminHandler = new UserAdminHandler(services);
		var accountHandler = new AccountHandler(services);
		var auditAdminHandler = new AuditAdminHandler(services);
		var webhookAdminHandler = new WebhookAdminHandler(services);
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
								info.title("LArtifactory").version("1.1.2").description("Artifact repository management API")
							)
							// Administration and token management require a password (basic) or a session from POST /api/session (bearer),
							// the read endpoints also accept access tokens as bearer token or as basic password with the user __token__
							.withSecurityScheme("basicAuth", new BasicAuth())
							.withSecurityScheme("bearerAuth", new BearerAuth())
							.withGlobalSecurity("basicAuth", _ -> {})
							.withGlobalSecurity("bearerAuth", _ -> {})
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
				MDC.put(AuditService.IP_KEY, Requests.clientIp(ctx, serverConfig));
				// Behind a reverse proxy every client has the address of the proxy unless it is trusted, which makes the login
				// lockout, token ip allowlists and the audit log addresses apply to the proxy instead of the clients
				if (!serverConfig.trustProxy() && ctx.header("X-Forwarded-For") != null && proxyWarned.compareAndSet(false, true)) {
					LOGGER.warn("Requests carry X-Forwarded-For but {} is not set, all clients behind the proxy share its address {} for the login lockout, token ip allowlists and the audit log",
						EnvKeys.TRUST_PROXY, ctx.req().getRemoteAddr());
				}
				ctx.attribute("trace_id", traceId);
				ctx.attribute("request_start", System.nanoTime());
				// Prevent browsers from MIME sniffing uploaded artifacts (e.g. a stored .html/.svg) into active content
				ctx.header("X-Content-Type-Options", "nosniff");
				ctx.header("Referrer-Policy", "no-referrer");
				if ("/ui".equals(ctx.path()) || ctx.path().startsWith("/ui/")) {
					ctx.header("Content-Security-Policy", UI_CONTENT_SECURITY_POLICY);
					ctx.header("X-Frame-Options", "DENY");
				} else if (!serverConfig.apiDocsEnabled() || API_DOCS_PREFIXES.stream().noneMatch(ctx.path()::startsWith)) {
					ctx.header("Content-Security-Policy", CONTENT_SECURITY_POLICY);
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
					// The management api answers the documented ErrorResponse json, the package protocols keep their plain text errors
					boolean api = ctx.path().startsWith("/api/");
					if (api && "text/plain".equals(e.contentType())) {
						ctx.json(new ErrorResponse(e.body()));
					} else {
						ctx.contentType(e.contentType() == null ? "text/plain" : e.contentType()).result(e.body());
					}
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
			config.routes.get("/health/live", healthHandler::live);
			config.routes.get("/health/ready", healthHandler::ready);
			
			// Web ui
			config.routes.get("/", ctx -> ctx.redirect("/ui/"));
			// Trailing slashes are ignored when matching, so this route also receives /ui/ and must only redirect the bare path
			config.routes.get("/ui", ctx -> {
				if (!ctx.req().getRequestURI().endsWith("/")) {
					ctx.redirect("/ui/");
					return;
				}
				InputStream index = Application.class.getResourceAsStream("/ui/index.html");
				if (index == null) {
					throw HttpError.notFound("The web ui is not packaged, build it with ./gradlew buildUi");
				}
				ctx.contentType("text/html; charset=utf-8").result(index);
			});
			
			// Management api
			config.routes.get("/api/me", userAdminHandler::me);
			config.routes.post("/api/session", userAdminHandler::createSession);
			config.routes.delete("/api/session", userAdminHandler::deleteSession);
			config.routes.post("/api/session/mfa", userAdminHandler::completeMfa);
			config.routes.post("/api/session/mfa/email", userAdminHandler::sendMfaEmail);
			config.routes.get("/api/account/security", accountHandler::get);
			config.routes.post("/api/account/email", accountHandler::requestEmail);
			config.routes.post("/api/account/email/verify", accountHandler::verifyEmail);
			config.routes.post("/api/account/email/remove", accountHandler::removeEmail);
			config.routes.post("/api/account/mfa/totp/setup", accountHandler::setupTotp);
			config.routes.post("/api/account/mfa/totp/enable", accountHandler::enableTotp);
			config.routes.post("/api/account/mfa/email/enable", accountHandler::enableEmail);
			config.routes.post("/api/account/mfa/email/code", accountHandler::sendConfirmationCode);
			config.routes.post("/api/account/mfa/recovery-codes", accountHandler::regenerateRecoveryCodes);
			config.routes.post("/api/account/mfa/{method}/disable", accountHandler::disable);
			config.routes.get("/api/password-reset", accountHandler::resetAvailable);
			config.routes.post("/api/password-reset", accountHandler::requestReset);
			config.routes.post("/api/password-reset/check", accountHandler::checkReset);
			config.routes.post("/api/password-reset/complete", accountHandler::completeReset);
			config.routes.get("/api/repositories", repositoryAdminHandler::list);
			config.routes.post("/api/repositories", repositoryAdminHandler::create);
			config.routes.get("/api/repositories/{name}", repositoryAdminHandler::get);
			config.routes.patch("/api/repositories/{name}", repositoryAdminHandler::update);
			config.routes.delete("/api/repositories/{name}", repositoryAdminHandler::delete);
			config.routes.get("/api/repositories/{name}/packages", repositoryAdminHandler::packages);
			config.routes.delete("/api/repositories/{name}/packages", repositoryAdminHandler::deletePackage);
			config.routes.get("/api/repositories/{name}/files", repositoryAdminHandler::files);
			config.routes.get("/api/repositories/{name}/packages/<package>", browseHandler::pkg);
			config.routes.get("/api/repositories/{name}/tree", browseHandler::tree);
			config.routes.get("/api/summary", browseHandler::summary);
			config.routes.get("/api/packages", browseHandler::packages);
			config.routes.get("/api/files", browseHandler::files);
			config.routes.get("/api/search", browseHandler::search);
			config.routes.post("/api/downloads", browseHandler::download);
			config.routes.get("/api/users", userAdminHandler::listUsers);
			config.routes.post("/api/users", userAdminHandler::createUser);
			config.routes.patch("/api/users/{username}", userAdminHandler::updateUser);
			config.routes.delete("/api/users/{username}", userAdminHandler::deleteUser);
			config.routes.delete("/api/users/{username}/mfa", userAdminHandler::resetMfa);
			config.routes.get("/api/permissions", userAdminHandler::listPermissions);
			config.routes.put("/api/permissions", userAdminHandler::setPermission);
			config.routes.delete("/api/permissions", userAdminHandler::deletePermission);
			config.routes.get("/api/tokens", userAdminHandler::listTokens);
			config.routes.post("/api/tokens", userAdminHandler::createToken);
			config.routes.delete("/api/tokens/{id}", userAdminHandler::deleteToken);
			config.routes.get("/api/audit", auditAdminHandler::list);
			config.routes.get("/api/webhooks", webhookAdminHandler::list);
			config.routes.post("/api/webhooks", webhookAdminHandler::create);
			config.routes.get("/api/webhooks/{id}", webhookAdminHandler::get);
			config.routes.patch("/api/webhooks/{id}", webhookAdminHandler::update);
			config.routes.delete("/api/webhooks/{id}", webhookAdminHandler::delete);
			config.routes.post("/api/webhooks/{id}/test", webhookAdminHandler::test);
			config.routes.get("/api/webhooks/{id}/deliveries", webhookAdminHandler::deliveries);
			
			// OCI distribution api, Docker only supports it at the root of the host
			for (HandlerType method : REPOSITORY_METHODS) {
				config.routes.addHttpHandler(method, "/v2", dispatcher::handleRegistry);
				config.routes.addHttpHandler(method, "/v2/<path>", dispatcher::handleRegistry);
			}
			
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
			this.services.webhooks().close();
			try {
				this.databaseProvider.close();
			} catch (SqlException e) {
				LOGGER.error("Failed to close database provider", e);
			}
		}
	}
}
