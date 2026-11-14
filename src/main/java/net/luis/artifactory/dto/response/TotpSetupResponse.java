package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

/**
 * @param secret The base32 secret, for entering it into the app by hand
 * @param uri The {@code otpauth://} uri, shown as qr code
 */
public record TotpSetupResponse(
	@NonNull String secret,
	@NonNull String uri
) {}
