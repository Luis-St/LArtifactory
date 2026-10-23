package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;

import java.time.Instant;

/**
 * A web ui session created by signing in with a password, only the hash of the session secret is stored.<br>
 */
public record SessionEntity(
	@NonNull String id,
	@NonNull String username,
	@NonNull String secretHash,
	@NonNull Instant createdAt,
	@NonNull Instant expiresAt
) {}
