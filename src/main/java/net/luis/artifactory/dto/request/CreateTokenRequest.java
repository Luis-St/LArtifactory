package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

public record CreateTokenRequest(
	@Nullable String name,
	@Nullable String username,
	@Nullable String level,
	@Nullable Integer expiresInDays,
	@Nullable String allowedIps,
	@Nullable Integer rateLimitPerMinute
) {}
