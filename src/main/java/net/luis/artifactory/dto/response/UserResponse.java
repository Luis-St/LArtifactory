package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.UserEntity;
import net.luis.artifactory.database.entity.UserSecurityEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * @param email The verified email address, null if none is set
 * @param mfaEnabled Whether the user signs in with a second factor
 */
public record UserResponse(
	@NonNull String username,
	boolean admin,
	@NonNull Instant createdAt,
	@Nullable String email,
	boolean mfaEnabled
) {
	
	@NonNull
	public static UserResponse of(@NonNull UserEntity entity, @Nullable UserSecurityEntity security) {
		return new UserResponse(entity.username(), entity.admin(), entity.createdAt(), security == null ? null : security.email(), security != null && security.mfaEnabled());
	}
}
