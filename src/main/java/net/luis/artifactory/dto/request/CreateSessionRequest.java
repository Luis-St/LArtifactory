package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param remember Whether the session should last longer (keep me signed in), otherwise it expires after a few hours
 */
public record CreateSessionRequest(
	@Nullable Boolean remember
) {}
