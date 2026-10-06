package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

public record CreateUserRequest(
	@Nullable String username,
	@Nullable String password,
	@Nullable Boolean admin
) {}
