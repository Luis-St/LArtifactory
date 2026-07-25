package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

public record ErrorResponse(
	@NonNull String error,
	@Nullable String description
) {
	
	public ErrorResponse(@NonNull String error) {
		this(error, null);
	}
	
	public ErrorResponse {
		Objects.requireNonNull(error, "Error must not be null");
	}
}
