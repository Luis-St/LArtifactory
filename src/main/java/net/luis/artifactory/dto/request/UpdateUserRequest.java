package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param password The new password
 * @param currentPassword The current password, required when users change their own password
 * @param admin Whether the user is an administrator
 * @param revokeTokens Whether all access tokens of the user are revoked as well, e.g. when the password changes after a compromise
 */
public record UpdateUserRequest(
	@Nullable String password,
	@Nullable String currentPassword,
	@Nullable Boolean admin,
	@Nullable Boolean revokeTokens
) {}
