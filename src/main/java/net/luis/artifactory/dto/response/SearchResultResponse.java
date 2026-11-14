package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

/**
 * A package matching a search.<br>
 */
public record SearchResultResponse(
	@NonNull String repository,
	@NonNull String type,
	@NonNull String name
) {}
