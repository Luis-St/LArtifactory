package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * @param apiDocs Whether the OpenAPI spec and Swagger ui are served, the web ui links to them only then
 */
public record HealthResponse(
	@NonNull String status,
	@NonNull String database,
	boolean apiDocs
) {
	
	public HealthResponse {
		Objects.requireNonNull(status, "Status must not be null");
		Objects.requireNonNull(database, "Database must not be null");
	}
}
