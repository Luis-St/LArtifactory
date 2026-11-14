package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param login The user name or the email address of the account
 */
public record PasswordResetRequest(
	@Nullable String login
) {}
