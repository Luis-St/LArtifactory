package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param token The token of the reset mail
 * @param password The new password, ignored when only checking the token
 * @param code A code of the authenticator app or a recovery code, required if the user has an authenticator app
 * @param revokeTokens Whether all access tokens of the user are revoked as well, false if not given
 */
public record PasswordResetCompleteRequest(
	@Nullable String token,
	@Nullable String password,
	@Nullable String code,
	@Nullable Boolean revokeTokens
) {}
