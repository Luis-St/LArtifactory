package net.luis.artifactory;

import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.config.SizeUnit;
import io.javalin.http.HandlerType;
import io.javalin.json.JavalinJackson3;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import net.luis.artifactory.auth.AuthService;
import net.luis.artifactory.auth.UserService;
import net.luis.artifactory.config.*;
import net.luis.artifactory.database.DatabaseProvider;
import net.luis.artifactory.dto.response.ErrorResponse;
import net.luis.artifactory.handler.HealthHandler;
import net.luis.artifactory.handler.admin.RepositoryAdminHandler;
import net.luis.artifactory.handler.admin.UserAdminHandler;
import net.luis.artifactory.handler.format.RepositoryDispatcher;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.repository.*;
import net.luis.artifactory.storage.BlobStore;
import net.luis.artifactory.util.Json;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.audit.SqlAuditUserProvider;
import net.luis.utils.io.database.exception.SqlException;
import org.eclipse.jetty.http.UriCompliance;
import org.slf4j.*;
import tools.jackson.core.JacksonException;

import java.util.List;
import java.util.UUID;

public class Application {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);
	private static final List<HandlerType> REPOSITORY_METHODS = List.of(HandlerType.GET, HandlerType.HEAD, HandlerType.PUT, HandlerType.POST, HandlerType.DELETE, HandlerType.PATCH);
	
	static void main() throws SqlException {
		Env env = Env.ofSystem();
		
		ServerConfig serverConfig = ServerConfig.from(env);
		DatabaseConfig databaseConfig = DatabaseConfig.from(env);
		
		DatabaseProvider databaseProvider = new DatabaseProvider(databaseConfig, SqlAuditUserProvider.empty());
		databaseProvider.initialize();
		SqlDatabase database = databaseProvider.getDatabase();
		
		BlobStore blobStore = new BlobStore(serverConfig.storagePath(), serverConfig.maxUploadSize());
		UserService userService = new UserService(database);
		userService.bootstrapAdmin(serverConfig.adminUsername(), serverConfig.adminPassword());
		ArtifactService artifactService = new ArtifactService(database, blobStore);
		Services services = new Services(
			serverConfig,
			userService,
			new AuthService(userService),
			new RepositoryService(database, artifactService),
			artifactService,
			new PackageService(database)
		);
		
		var healthHandler = new HealthHandler(databaseProvider);
		var repositoryAdminHandler = new RepositoryAdminHandler(services);
		var userAdminHandler = new UserAdminHandler(services);
		var dispatcher = new RepositoryDispatcher(services);
		
		Javalin app = Javalin.create(config -> {
			config.startup.showJavalinBanner = false;
			config.http.defaultContentType = "application/json";
			config.http.compressionStrategy = CompressionStrategy.NONE;
			config.http.maxRequestSize = serverConfig.maxUploadSize() * 2;
			config.jsonMapper(new JavalinJackson3(Json.MAPPER, false));
			
			// Package managers send encoded slashes (npm: @scope%2fname), which jetty rejects by default
			config.jetty.modifyHttpConfiguration(httpConfig -> httpConfig.setUriCompliance(UriCompliance.LEGACY));
			config.jetty.modifyServletContextHandler(handler -> handler.getServletHandler().setDecodeAmbiguousURIs(true));
			config.jetty.multipartConfig.maxFileSize(serverConfig.maxUploadSize(), SizeUnit.BYTES);
			config.jetty.multipartConfig.maxTotalRequestSize(serverConfig.maxUploadSize() * 2, SizeUnit.BYTES);
			config.jetty.multipartConfig.maxInMemoryFileSize(1, SizeUnit.MB);
			config.jetty.multipartConfig.cacheDirectory(serverConfig.storagePath().resolve("tmp").toString());
			
			config.registerPlugin(new OpenApiPlugin(pluginConfig ->
				pluginConfig.withDefinitionConfiguration((_, definition) ->
					definition.info(info ->
						info.title("LArtifactory").version("1.0.0").description("Artifact repository management API")
					)
				)
			));
			config.registerPlugin(new SwaggerPlugin());
			
			config.routes.before(ctx -> {
				String traceId = UUID.randomUUID().toString();
				MDC.put("trace_id", traceId);
				MDC.put("source_ip", ctx.ip());
				ctx.attribute("trace_id", traceId);
				ctx.attribute("request_start", System.nanoTime());
			});
			
			config.routes.before(dispatcher::handleMappedHost);
			
			config.routes.after(ctx -> {
				Long start = ctx.attribute("request_start");
				if (start != null) {
					MDC.put("duration_ms", String.valueOf((System.nanoTime() - start) / 1_000_000));
				}
				
				LOGGER.info("{} {} {}", ctx.method(), ctx.path(), ctx.status());
				MDC.clear();
			});
			
			config.routes.exception(HttpError.class, (e, ctx) -> {
				ctx.status(e.status());
				e.headers().forEach(ctx::header);
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
				LOGGER.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
				ctx.status(500).json(new ErrorResponse("Internal server error"));
			});
			
			// Health
			config.routes.get("/health", healthHandler::health);
			
			// Management api
			config.routes.get("/api/me", userAdminHandler::me);
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
		
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOGGER.info("Shutting down LArtifactory");
			app.stop();
			
			try {
				databaseProvider.close();
			} catch (SqlException e) {
				LOGGER.error("Failed to close database provider", e);
				throw new RuntimeException("Failed to close database provider", e);
			}
			LOGGER.info("LArtifactory stopped");
		}));
		
		app.start(serverConfig.port());
		LOGGER.info("LArtifactory started on port {}", serverConfig.port());
	}
}
