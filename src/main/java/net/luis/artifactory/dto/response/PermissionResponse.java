package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

public record PermissionResponse(
	@NonNull String repository,
	@NonNull String username,
	@NonNull String level
) {}
