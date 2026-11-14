package net.luis.artifactory.dto.response;

import org.jspecify.annotations.Nullable;

/**
 * @param email The verified email address, null if none is set
 * @param totpEnabled Whether an authenticator app is required to sign in
 * @param emailMfaEnabled Whether codes sent by email are accepted as second factor
 * @param recoveryCodesLeft The number of unused recovery codes
 * @param mailAvailable Whether the server can send mails, email addresses and codes by email need it
 */
public record AccountSecurityResponse(
	@Nullable String email,
	boolean totpEnabled,
	boolean emailMfaEnabled,
	int recoveryCodesLeft,
	boolean mailAvailable
) {}
