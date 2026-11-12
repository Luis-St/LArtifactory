package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * The result of one webhook delivery attempt chain (the final outcome after any retries).<br>
 *
 * @param id Unique id of the delivery
 * @param webhookId The webhook this delivery belongs to
 * @param event The event type that was delivered
 * @param repository The repository the event relates to, null if not repository scoped
 * @param success Whether the target accepted the delivery (2xx response)
 * @param statusCode The HTTP status code of the last attempt, {@code 0} if no response was received
 * @param error A short error description if the delivery failed, null on success
 * @param createdAt When the delivery was attempted
 */
public record WebhookDeliveryEntity(
	@NonNull String id,
	@NonNull String webhookId,
	@NonNull String event,
	@Nullable String repository,
	boolean success,
	long statusCode,
	@Nullable String error,
	@NonNull Instant createdAt
) {}
