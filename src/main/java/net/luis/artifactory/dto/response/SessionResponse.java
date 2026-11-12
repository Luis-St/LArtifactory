package net.luis.artifactory.dto.response;

import net.luis.artifactory.auth.UserService;
import org.jspecify.annotations.NonNull;

import java.time.Instant;

/**
 * A created web ui session, the token is sent as {@code Authorization: Bearer <token>}.<br>
 */
public record SessionResponse(
	@NonNull String username,
	@NonNull String token,
	@NonNull Instant expiresAt
) {
	
	@NonNull
	public static SessionResponse of(UserService.@NonNull CreatedSession session) {
		return new SessionResponse(session.session().username(), session.secret(), session.session().expiresAt());
	}
}
