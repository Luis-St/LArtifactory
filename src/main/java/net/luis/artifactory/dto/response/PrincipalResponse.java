package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record PrincipalResponse(
	@NonNull String username,
	boolean admin,
	@Nullable String tokenLevel
) {}
