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
	
	/**
	 * Whether this principal may use the administrative api.<br>
	 * Tokens never grant administrative access, even a {@code DELETE} token of an admin user: the management api
	 * requires authenticating with the user password (basic auth), so a leaked repository/publish token can not be
	 * used to manage users, permissions or repositories.<br>
	 */
	public boolean isAdmin() {
		return this.admin && this.tokenLevel == null;
	}
}
