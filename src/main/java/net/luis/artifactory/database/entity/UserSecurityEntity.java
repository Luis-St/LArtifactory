package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Email address and two-factor settings of a user, stored separately from {@link UserEntity} and keyed by the user name.<br>
 *
 * @param username The user this row belongs to
 * @param email The verified email address (lower case), null if none is set. Used for password recovery and email codes.
 * @param totpSecret The secret of the authenticator app, encrypted with the pepper, null if no app is set up
 * @param totpEnabled Whether a code of the authenticator app is required to sign in
 * @param emailMfaEnabled Whether a code sent by email is accepted as second factor
 * @param totpLastStep The time step of the last accepted app code, a code can not be used twice
 * @param recoveryCodes The comma separated keyed hashes of the unused recovery codes, null if there are none
 */
public record UserSecurityEntity(
	@NonNull String username,
	@Nullable String email,
	@Nullable String totpSecret,
	boolean totpEnabled,
	boolean emailMfaEnabled,
	long totpLastStep,
	@Nullable String recoveryCodes
) {
	
	@NonNull
	public static UserSecurityEntity empty(@NonNull String username) {
		return new UserSecurityEntity(username, null, null, false, false, -1, null);
	}
	
	public boolean mfaEnabled() {
		return this.totpEnabled || this.emailMfaEnabled;
	}
}
