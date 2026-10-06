package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

public record CreateRepositoryRequest(
	@Nullable String name,
	@Nullable String type,
	@Nullable String description,
	@Nullable Boolean publicRead,
	@Nullable Boolean allowRedeploy
) {}
