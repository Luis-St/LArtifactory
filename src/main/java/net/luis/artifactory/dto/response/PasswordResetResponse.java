package net.luis.artifactory.dto.response;

import org.jspecify.annotations.Nullable;

/**
 * @param available Whether password recovery by email is possible on this server
 * @param username The user of a checked reset token
 * @param totpRequired Whether completing the reset needs a code of the authenticator app or a recovery code
 */
public record PasswordResetResponse(
	boolean available,
	@Nullable String username,
	boolean totpRequired
) {}
