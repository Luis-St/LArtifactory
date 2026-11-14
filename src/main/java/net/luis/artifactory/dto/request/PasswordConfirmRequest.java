package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param password The current password of the user, confirms a change of the account security
 */
public record PasswordConfirmRequest(
	@Nullable String password
) {}
