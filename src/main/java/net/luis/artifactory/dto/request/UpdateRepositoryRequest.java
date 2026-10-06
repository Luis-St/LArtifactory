package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

public record UpdateRepositoryRequest(
	@Nullable String description,
	@Nullable Boolean publicRead,
	@Nullable Boolean allowRedeploy
) {}
