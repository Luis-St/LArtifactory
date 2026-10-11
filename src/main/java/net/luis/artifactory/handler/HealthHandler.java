package net.luis.artifactory.handler;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.database.DatabaseProvider;
import net.luis.artifactory.dto.response.HealthResponse;
import net.luis.artifactory.dto.response.LivenessResponse;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Health probes.<br>
 * <ul>
 *     <li>{@code /health} - aggregate status (always {@code 200}), kept for backwards compatibility</li>
 *     <li>{@code /health/live} - liveness, {@code 200} as long as the process can answer (no dependencies checked)</li>
 *     <li>{@code /health/ready} - readiness, {@code 200} when the database is reachable, otherwise {@code 503}</li>
 * </ul>
 * Orchestrators (Kubernetes, Docker, load balancers) should use liveness to decide whether to restart the container
 * and readiness to decide whether to route traffic to it.<br>
 */
public class HealthHandler {

	private final DatabaseProvider databaseProvider;

	public HealthHandler(@NonNull DatabaseProvider databaseProvider) {
		this.databaseProvider = Objects.requireNonNull(databaseProvider, "Database provider must not be null");
	}

	@OpenApi(
		summary = "Aggregate health check",
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

	@OpenApi(
		summary = "Liveness probe, succeeds while the process is running",
		operationId = "healthLive",
		path = "/health/live",
		methods = HttpMethod.GET,
		tags = "Health",
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = LivenessResponse.class)
		)
	)
	public void live(@NonNull Context ctx) {
		Objects.requireNonNull(ctx, "Context must not be null");
		// Deliberately does not touch the database: a failing database must not cause the container to be restarted.
		ctx.json(new LivenessResponse("UP"));
	}

	@OpenApi(
		summary = "Readiness probe, succeeds when the server can serve requests",
		operationId = "healthReady",
		path = "/health/ready",
		methods = HttpMethod.GET,
		tags = "Health",
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = HealthResponse.class)),
			@OpenApiResponse(status = "503", content = @OpenApiContent(from = HealthResponse.class))
		}
	)
	public void ready(@NonNull Context ctx) {
		Objects.requireNonNull(ctx, "Context must not be null");

		boolean dbHealthy = this.databaseProvider.isHealthy();
		// Return 503 when not ready so load balancers and orchestrators stop routing traffic until the database recovers.
		ctx.status(dbHealthy ? 200 : 503).json(new HealthResponse(dbHealthy ? "UP" : "DOWN", dbHealthy ? "UP" : "DOWN"));
	}
}
