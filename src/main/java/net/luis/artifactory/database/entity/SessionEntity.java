package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A web ui session, only the hash of the session secret is stored.<br>
 *
 * @param level The maximum access level of a restricted session, null for a full session created with the user password.
 * A restricted session never grants administration or token management, like an access token.
 * @param tokenId The access token the session was exchanged for, null if it was not created from a token.
 * The session ends when the token is revoked and is subject to the ip allowlist and rate limit of the token.
 */
public record SessionEntity(
	@NonNull String id,
	@NonNull String username,
	@NonNull String secretHash,
	@NonNull Instant createdAt,
	@NonNull Instant expiresAt,
	@Nullable String level,
	@Nullable String tokenId
) {}
