package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param email The new email address, a verification code is sent to it
 * @param password The current password of the user
 */
public record EmailChangeRequest(
	@Nullable String email,
	@Nullable String password
) {}
