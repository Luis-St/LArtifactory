package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PackageResponse(
	@NonNull String name,
	@NonNull List<VersionResponse> versions,
	@NonNull Map<String, String> tags
) {
	
	public record VersionResponse(
		@NonNull String version,
		boolean yanked,
		@NonNull Instant createdAt,
		@Nullable String createdBy
	) {}
}
