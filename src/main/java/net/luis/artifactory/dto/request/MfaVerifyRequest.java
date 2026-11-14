package net.luis.artifactory.dto.request;

import org.jspecify.annotations.Nullable;

/**
 * @param challenge The challenge returned by {@code POST /api/session}
 * @param method The second factor of the code: {@code totp} (authenticator app), {@code email} or {@code recovery}
 * @param code The code, a recovery code has the format {@code ABCD-EFGH}
 */
public record MfaVerifyRequest(
	@Nullable String challenge,
	@Nullable String method,
	@Nullable String code
) {}
