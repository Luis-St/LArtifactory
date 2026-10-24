package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param password The new password
 * @param currentPassword The current password, required when users change their own password
 * @param admin Whether the user is an administrator
 */
public record UpdateUserRequest(
	@Nullable String password,
	@Nullable String currentPassword,
	@Nullable Boolean admin
) {}
