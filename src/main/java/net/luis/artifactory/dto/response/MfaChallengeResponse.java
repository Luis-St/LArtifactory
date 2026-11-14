package net.luis.artifactory.dto.response;

import net.luis.artifactory.auth.AccountSecurityService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * The password was correct and the user has two-factor authentication, {@code POST /api/session/mfa} completes the sign in.<br>
 *
 * @param mfaRequired Always true, tells this response apart from a created session
 * @param challenge Identifies this sign in, sent with the code
 * @param methods The second factors that can be used: {@code totp}, {@code email}, {@code recovery}
 * @param emailHint The masked address email codes are sent to, null if codes by email are not enabled
 */
public record MfaChallengeResponse(
	boolean mfaRequired,
	@NonNull String challenge,
	@NonNull List<String> methods,
	@Nullable String emailHint,
	@NonNull Instant expiresAt
) {
	
	@NonNull
	public static MfaChallengeResponse of(AccountSecurityService.@NonNull Challenge challenge) {
		return new MfaChallengeResponse(true, challenge.id(), challenge.methods(), challenge.emailHint(), challenge.expiresAt());
	}
}
