package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record CreateWebhookRequest(
	@Nullable String repository,
	@Nullable String url,
	@Nullable String secret,
	@Nullable List<String> events,
	@Nullable Boolean active
) {}
