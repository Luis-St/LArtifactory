package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.TokenEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A token, the secret is only included once in the response of the token creation.<br>
 */
public record TokenResponse(
	@NonNull String id,
	@NonNull String username,
	@NonNull String name,
	@NonNull String level,
	@NonNull Instant createdAt,
	@Nullable Instant expiresAt,
	@Nullable String token
) {
	
	public static @NonNull TokenResponse of(@NonNull TokenEntity entity, @Nullable String secret) {
		return new TokenResponse(entity.id(), entity.username(), entity.name(), entity.level(), entity.createdAt(), entity.expiresAt(), secret);
	}
}
