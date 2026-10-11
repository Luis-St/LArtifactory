package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record UpdateWebhookRequest(
	@Nullable String url,
	@Nullable List<String> events,
	@Nullable Boolean active
) {}
