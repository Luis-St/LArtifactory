package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Liveness probe response, reports only that the process itself is running (no dependencies are checked).<br>
 */
public record LivenessResponse(
	@NonNull String status
) {

	public LivenessResponse {
		Objects.requireNonNull(status, "Status must not be null");
	}
}
