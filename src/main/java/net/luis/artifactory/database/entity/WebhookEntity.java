package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A webhook subscription.<br>
 *
 * @param id Unique id of the webhook
 * @param repository The repository this webhook is scoped to, null for all repositories
 * @param url The target url that receives the POST request
 * @param secret Optional secret used to sign the payload with HMAC-SHA256, null for unsigned deliveries
 * @param events Comma separated list of subscribed event types, null or blank subscribes to all events
 * @param active Whether the webhook is enabled
 * @param createdAt When the webhook was created
 * @param createdBy The user that created the webhook, null if unknown
 */
public record WebhookEntity(
	@NonNull String id,
	@Nullable String repository,
	@NonNull String url,
	@Nullable String secret,
	@Nullable String events,
	boolean active,
	@NonNull Instant createdAt,
	@Nullable String createdBy
) {}
