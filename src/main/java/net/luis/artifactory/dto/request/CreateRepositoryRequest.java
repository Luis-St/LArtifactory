package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record CreateRepositoryRequest(
	@Nullable String name,
	@Nullable String type,
	@Nullable String description,
	@Nullable Boolean publicRead,
	@Nullable Boolean allowRedeploy,
	@Nullable Remote remote,
	@Nullable List<String> members
) {

	/**
	 * Upstream configuration of a remote (proxy) repository.<br>
	 */
	public record Remote(
		@Nullable String url,
		@Nullable Boolean cacheEnabled,
		@Nullable String username,
		@Nullable String password
	) {}
}
