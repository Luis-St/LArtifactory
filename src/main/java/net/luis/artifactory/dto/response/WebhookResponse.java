package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.WebhookEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * A webhook, the secret is never returned (only whether one is configured).<br>
 */
public record WebhookResponse(
	@NonNull String id,
	@Nullable String repository,
	@NonNull String url,
	boolean hasSecret,
	@NonNull List<String> events,
	boolean active,
	@NonNull Instant createdAt,
	@Nullable String createdBy
) {

	public static @NonNull WebhookResponse of(@NonNull WebhookEntity entity) {
		List<String> events = entity.events() == null || entity.events().isBlank()
			? List.of()
			: List.of(entity.events().split("[,\\s]+"));
		return new WebhookResponse(
			entity.id(),
			entity.repository(),
			entity.url(),
			entity.secret() != null && !entity.secret().isBlank(),
			events,
			entity.active(),
			entity.createdAt(),
			entity.createdBy()
		);
	}
}
