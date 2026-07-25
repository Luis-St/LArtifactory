package net.luis.artifactory.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.database.DatabaseProvider;
import net.luis.artifactory.dto.response.HealthResponse;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

public class HealthHandler {
	
	private final DatabaseProvider databaseProvider;
	
	public HealthHandler(@NonNull DatabaseProvider databaseProvider) {
		this.databaseProvider = Objects.requireNonNull(databaseProvider, "Database provider must not be null");
	}
	
	@OpenApi(
		summary = "Health check",
		operationId = "healthCheck",
		path = "/health",
		methods = HttpMethod.GET,
		tags = "Health",
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = HealthResponse.class)
		)
	)
	public void health(@NonNull Context ctx) {
		Objects.requireNonNull(ctx, "Context must not be null");
		
		boolean dbHealthy = this.databaseProvider.isHealthy();
		String dbStatus = dbHealthy ? "UP" : "DOWN";
		String overallStatus = dbHealthy ? "UP" : "DEGRADED";
		ctx.json(new HealthResponse(overallStatus, dbStatus));
	}
}
