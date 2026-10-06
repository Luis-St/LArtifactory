package net.luis.artifactory.auth;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * The authenticated user of a request.<br>
 *
 * @param username The name of the user
 * @param admin Whether the user is an administrator
 * @param tokenLevel The maximum access level of the token used to authenticate, null if authenticated with a password
 */
public record Principal(
	@NonNull String username,
	boolean admin,
	@Nullable AccessLevel tokenLevel
) {
	
	public Principal {
		Objects.requireNonNull(username, "Username must not be null");
	}
	
	public boolean isAdmin() {
		return this.admin && (this.tokenLevel == null || this.tokenLevel == AccessLevel.DELETE);
	}
}
