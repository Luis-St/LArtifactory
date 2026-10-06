package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

public record PermissionRequest(
	@Nullable String repository,
	@Nullable String username,
	@Nullable String level
) {}
