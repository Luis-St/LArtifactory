package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record HealthResponse(
	@NonNull String status,
	@NonNull String database
) {
	
	public HealthResponse {
		Objects.requireNonNull(status, "Status must not be null");
		Objects.requireNonNull(database, "Database must not be null");
	}
}
