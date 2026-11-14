package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param email The new email address, a verification code is sent to it
 * @param password The current password of the user
 * @param code A code of the authenticator app, a confirmation code sent by email or a recovery code, required if the user has two-factor authentication
 */
public record EmailChangeRequest(
	@Nullable String email,
	@Nullable String password,
	@Nullable String code
) {}
