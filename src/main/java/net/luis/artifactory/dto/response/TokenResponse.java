package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.TokenEntity;
import net.luis.artifactory.database.entity.TokenMetaEntity;
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
	@Nullable String allowedIps,
	long rateLimit,
	@Nullable Instant lastUsedAt,
	@Nullable String lastUsedIp,
	@Nullable String token
) {

	public static @NonNull TokenResponse of(@NonNull TokenEntity entity, @Nullable String secret) {
		return of(entity, null, secret);
	}

	public static @NonNull TokenResponse of(@NonNull TokenEntity entity, @Nullable TokenMetaEntity meta, @Nullable String secret) {
		return new TokenResponse(
			entity.id(),
			entity.username(),
			entity.name(),
			entity.level(),
			entity.createdAt(),
			entity.expiresAt(),
			meta == null ? null : meta.allowedIps(),
			meta == null ? 0 : meta.rateLimit(),
			meta == null ? null : meta.lastUsedAt(),
			meta == null ? null : meta.lastUsedIp(),
			secret
		);
	}
}
