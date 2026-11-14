package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param code A code of the authenticator app or a verification code sent by email
 */
public record CodeRequest(
	@Nullable String code
) {}
