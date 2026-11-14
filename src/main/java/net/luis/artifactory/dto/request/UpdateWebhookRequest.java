package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Changes of a webhook, omitted fields are kept.<br>
 *
 * @param repository The repository to scope the webhook to, an empty string makes it apply to all repositories
 * @param secret The new signing secret, an empty string removes it
 */
public record UpdateWebhookRequest(
	@Nullable String url,
	@Nullable List<String> events,
	@Nullable Boolean active,
	@Nullable String repository,
	@Nullable String secret
) {}
