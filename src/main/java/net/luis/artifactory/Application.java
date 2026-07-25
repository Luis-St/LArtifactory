package net.luis.artifactory;

import io.javalin.Javalin;
import io.javalin.openapi.plugin.OpenApiPlugin;
import io.javalin.openapi.plugin.swagger.SwaggerPlugin;
import net.luis.artifactory.config.*;
import net.luis.artifactory.database.DatabaseProvider;
import net.luis.artifactory.handler.HealthHandler;
import net.luis.utils.io.database.audit.SqlAuditUserProvider;
import net.luis.utils.io.database.exception.SqlException;
import org.slf4j.*;

import java.util.UUID;

public class Application {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);
	
	static void main() {
		Env env = Env.ofSystem();
		
		int port = env.integer(EnvKeys.PORT, 8080);
		DatabaseConfig databaseConfig = DatabaseConfig.from(env);
		
		DatabaseProvider databaseProvider = new DatabaseProvider(databaseConfig, SqlAuditUserProvider.empty() /*ToDo*/);
		
		var healthHandler = new HealthHandler(databaseProvider);
		
		Javalin app = Javalin.create(config -> {
			config.startup.showJavalinBanner = false;
			config.http.defaultContentType = "application/json";
			
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
			
			config.routes.after(ctx -> {
				Long start = ctx.attribute("request_start");
				if (start != null) {
					MDC.put("duration_ms", String.valueOf((System.nanoTime() - start) / 1_000_000));
				}
				
				LOGGER.info("{} {} {}", ctx.method(), ctx.path(), ctx.status());
				MDC.clear();
			});
			
			// Health
			config.routes.get("/health", healthHandler::health);
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
		
		app.start(port);
		LOGGER.info("LArtifactory started on port {}", port);
	}
}
