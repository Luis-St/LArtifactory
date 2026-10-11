package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.WebhookDeliveryEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record WebhookDeliveryResponse(
	@NonNull String id,
	@NonNull String event,
	@Nullable String repository,
	boolean success,
	long statusCode,
	@Nullable String error,
	@NonNull Instant createdAt
) {

	public static @NonNull WebhookDeliveryResponse of(@NonNull WebhookDeliveryEntity entity) {
		return new WebhookDeliveryResponse(entity.id(), entity.event(), entity.repository(), entity.success(), entity.statusCode(), entity.error(), entity.createdAt());
	}
}
