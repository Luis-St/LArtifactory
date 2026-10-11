package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record UpdateRepositoryRequest(
	@Nullable String description,
	@Nullable Boolean publicRead,
	@Nullable Boolean allowRedeploy,
	@Nullable CreateRepositoryRequest.Remote remote,
	@Nullable List<String> members
) {}
