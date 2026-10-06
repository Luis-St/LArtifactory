package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.UserEntity;
import org.jspecify.annotations.NonNull;

import java.time.Instant;

public record UserResponse(
	@NonNull String username,
	boolean admin,
	@NonNull Instant createdAt
) {
	
	public static @NonNull UserResponse of(@NonNull UserEntity entity) {
		return new UserResponse(entity.username(), entity.admin(), entity.createdAt());
	}
}
