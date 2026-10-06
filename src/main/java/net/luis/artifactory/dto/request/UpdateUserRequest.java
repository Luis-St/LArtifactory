package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

public record UpdateUserRequest(
	@Nullable String password,
	@Nullable Boolean admin
) {}
