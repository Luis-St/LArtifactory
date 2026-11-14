package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param password The current password of the user, confirms a change of the account security
 * @param code A code of the authenticator app, a confirmation code sent by email or a recovery code, required if the user has two-factor authentication
 */
public record PasswordConfirmRequest(
	@Nullable String password,
	@Nullable String code
) {}
