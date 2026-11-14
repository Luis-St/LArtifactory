package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.*;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.dto.request.*;
import net.luis.artifactory.dto.response.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.AuditService;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Administration of users, permissions and tokens.<br>
 */
public class UserAdminHandler {
	
	private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._@-]{0,127}");
	
	private final Services services;
	
	public UserAdminHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
	}
	
	static void validatePassword(String password) {
		if (password == null || password.length() < 12) {
			throw HttpError.badRequest("Password must have at least 12 characters");
		}
		if (password.length() > 256) {
			throw HttpError.badRequest("Password must have at most 256 characters");
		}
	}
	
	@OpenApi(
		summary = "Get the authenticated user",
		operationId = "whoami",
		path = "/api/me",
		methods = HttpMethod.GET,
		tags = "Users",
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = PrincipalResponse.class)),
			@OpenApiResponse(status = "401", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void me(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		ctx.json(new PrincipalResponse(principal.username(), principal.admin(), principal.tokenLevel() == null ? null : principal.tokenLevel().name()));
	}
	
	@OpenApi(
		summary = "Sign in to the web ui with the user password or an access token (basic auth), returns a session token for bearer authentication",
		description = "A session created with an access token is restricted to the level of the token, it never grants administration or token management, ends when the token is revoked or expires and is subject to the ip allowlist and rate limit of the token. " +
			"For a user with two-factor authentication the password answers 200 with a challenge instead, POST /api/session/mfa completes it with a code.",
		operationId = "createSession",
		path = "/api/session",
		methods = HttpMethod.POST,
		tags = "Users",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreateSessionRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = SessionResponse.class)),
			@OpenApiResponse(status = "200", description = "Two-factor authentication required", content = @OpenApiContent(from = MfaChallengeResponse.class)),
			@OpenApiResponse(status = "401", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "429", description = "Too many failed logins")
		}
	)
	public void createSession(@NonNull Context ctx) throws Exception {
		// Only a password or a token creates a session, so a session can not be used to extend itself
		if (AuthService.sessionSecret(ctx) != null) {
			throw HttpError.forbidden("A session can only be created with the user password or an access token");
		}
		String body = ctx.body();
		CreateSessionRequest request = body.isBlank() ? new CreateSessionRequest(null) : ctx.bodyAsClass(CreateSessionRequest.class);
		boolean remember = Boolean.TRUE.equals(request.remember());
		AuthService.Credentials credentials = AuthService.passwordCredentials(ctx);
		if (credentials != null) {
			UserEntity user = this.services.auth().login(ctx, credentials.username(), credentials.password());
			if (user == null) {
				throw HttpError.unauthorized("Basic realm=\"artifactory\"");
			}
			if (this.services.users().security(user.username()).mfaEnabled()) {
				ctx.status(200).json(MfaChallengeResponse.of(this.services.security().startChallenge(user, remember)));
				return;
			}
			ctx.status(201).json(SessionResponse.of(this.services.users().createSession(user.username(), this.sessionLifetime(remember))));
			return;
		}
		Principal principal = this.services.auth().requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		// Only a token remains, the browser keeps a session that expires and is revoked on sign out instead of the token itself
		String secret = AuthService.tokenSecret(ctx);
		TokenEntity token = principal.tokenLevel() == null || secret == null ? null : this.services.users().findTokenBySecret(secret);
		if (token == null) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		Instant expiresAt = Instant.now().plus(this.sessionLifetime(remember));
		if (token.expiresAt() != null && token.expiresAt().isBefore(expiresAt)) {
			expiresAt = token.expiresAt();
		}
		ctx.status(201).json(SessionResponse.of(this.services.users().createSession(principal.username(), expiresAt, principal.tokenLevel(), token.id())));
	}
	
	@NonNull
	private Duration sessionLifetime(boolean remember) {
		return remember ? Duration.ofDays(this.services.config().sessionRememberDays()) : Duration.ofHours(this.services.config().sessionHours());
	}
	
	@OpenApi(
		summary = "Complete a sign in with two-factor authentication, returns the session",
		operationId = "completeMfa",
		path = "/api/session/mfa",
		methods = HttpMethod.POST,
		tags = "Users",
		security = {},
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = MfaVerifyRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = SessionResponse.class)),
			@OpenApiResponse(status = "401", description = "Wrong code or expired challenge", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "429", description = "Too many failed logins")
		}
	)
	public void completeMfa(@NonNull Context ctx) throws Exception {
		MfaVerifyRequest request = ctx.bodyAsClass(MfaVerifyRequest.class);
		String address = Requests.clientIp(ctx, this.services.config());
		AccountSecurityService.Challenge challenge = this.services.security().completeChallenge(address, request.challenge(), request.method(), request.code());
		ctx.status(201).json(SessionResponse.of(this.services.users().createSession(challenge.username(), this.sessionLifetime(challenge.remember()))));
	}
	
	@OpenApi(
		summary = "Send the sign in code of a two-factor challenge to the email address of the user",
		operationId = "sendMfaEmail",
		path = "/api/session/mfa/email",
		methods = HttpMethod.POST,
		tags = "Users",
		security = {},
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = MfaVerifyRequest.class)),
		responses = {
			@OpenApiResponse(status = "204"),
			@OpenApiResponse(status = "401", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "429", description = "A code was sent moments ago")
		}
	)
	public void sendMfaEmail(@NonNull Context ctx) throws Exception {
		this.services.security().sendChallengeCode(ctx.bodyAsClass(MfaVerifyRequest.class).challenge());
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "Turn off two-factor authentication of a user that lost access to every second factor",
		operationId = "resetUserMfa",
		path = "/api/users/{username}/mfa",
		methods = HttpMethod.DELETE,
		tags = "Users",
		pathParams = @OpenApiParam(name = "username", required = true),
		responses = @OpenApiResponse(status = "204")
	)
	public void resetMfa(@NonNull Context ctx) throws Exception {
		Principal admin = this.services.auth().requireAdmin(ctx);
		String username = ctx.pathParam("username");
		if (this.services.users().get(username) == null) {
			throw HttpError.notFound("No such user: " + username);
		}
		this.services.security().reset(username);
		this.services.audit().record(admin.username(), AuditService.MFA_RESET, null, username, Requests.clientIp(ctx, this.services.config()), null);
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "Sign out of the web ui, deletes the session of the request",
		operationId = "deleteSession",
		path = "/api/session",
		methods = HttpMethod.DELETE,
		tags = "Users",
		responses = @OpenApiResponse(status = "204")
	)
	public void deleteSession(@NonNull Context ctx) throws Exception {
		String secret = AuthService.sessionSecret(ctx);
		if (secret != null) {
			this.services.users().deleteSessionBySecret(secret);
			this.services.auth().invalidateCache();
		}
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "List users",
		operationId = "listUsers",
		path = "/api/users",
		methods = HttpMethod.GET,
		tags = "Users",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = UserResponse[].class))
	)
	public void listUsers(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		Map<String, UserSecurityEntity> security = this.services.users().allSecurity();
		ctx.json(this.services.users().list().stream().map(user -> UserResponse.of(user, security.get(user.username()))).toList());
	}
	
	@OpenApi(
		summary = "Create a user",
		operationId = "createUser",
		path = "/api/users",
		methods = HttpMethod.POST,
		tags = "Users",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreateUserRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = UserResponse.class)),
			@OpenApiResponse(status = "409", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void createUser(@NonNull Context ctx) throws Exception {
		Principal admin = this.services.auth().requireAdmin(ctx);
		CreateUserRequest request = ctx.bodyAsClass(CreateUserRequest.class);
		if (request.username() == null || !USERNAME.matcher(request.username()).matches() || "__token__".equals(request.username())) {
			throw HttpError.badRequest("Invalid username");
		}
		validatePassword(request.password());
		// Names that differ only by case look alike in permission lists and share one login throttle counter
		if (this.services.users().existsIgnoreCase(request.username())) {
			throw HttpError.conflict("User " + request.username() + " already exists (user names are compared without case)");
		}
		UserEntity user = this.services.users().create(request.username(), request.password(), Boolean.TRUE.equals(request.admin()));
		this.services.audit().record(admin.username(), AuditService.USER_CREATE, null, user.username(), Requests.clientIp(ctx, this.services.config()), user.admin() ? "admin" : null);
		ctx.status(201).json(UserResponse.of(user, null));
	}
	
	@OpenApi(
		summary = "Update a user, users may change their own password (with their current password)",
		operationId = "updateUser",
		path = "/api/users/{username}",
		methods = HttpMethod.PATCH,
		tags = "Users",
		pathParams = @OpenApiParam(name = "username", required = true),
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = UpdateUserRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = UserResponse.class))
	)
	public void updateUser(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		String username = ctx.pathParam("username");
		UpdateUserRequest request = ctx.bodyAsClass(UpdateUserRequest.class);
		boolean self = principal.username().equals(username) && principal.tokenLevel() == null;
		if (!principal.isAdmin() && (!self || request.admin() != null)) {
			throw HttpError.forbidden("Administrator privileges required");
		}
		UserEntity user = this.services.users().get(username);
		if (user == null) {
			throw HttpError.notFound("No such user: " + username);
		}
		if (request.password() != null) {
			validatePassword(request.password());
			// A stolen session must not be enough to take over the account, failed attempts count towards the login throttle
			if (self && (request.currentPassword() == null || this.services.auth().login(ctx, username, request.currentPassword()) == null)) {
				throw HttpError.forbidden("The current password is required and must be correct");
			}
		}
		if (user.admin() && Boolean.FALSE.equals(request.admin()) && this.services.users().countAdmins() <= 1) {
			throw HttpError.badRequest("Can not remove the administrator privileges of the last administrator");
		}
		this.services.users().update(username, request.password(), request.admin());
		if (request.password() != null) {
			// Sign out everywhere else, the session changing its own password stays valid
			this.services.users().deleteSessions(username, self ? AuthService.sessionSecret(ctx) : null);
		}
		if (Boolean.TRUE.equals(request.revokeTokens())) {
			this.services.users().deleteTokens(username).forEach(this.services.tokenUsage()::invalidate);
		}
		this.services.auth().invalidateCache();
		StringBuilder detail = new StringBuilder();
		if (request.password() != null) {
			detail.append("password");
		}
		if (request.admin() != null) {
			detail.append(detail.isEmpty() ? "" : ", ").append("admin=").append(request.admin());
		}
		if (Boolean.TRUE.equals(request.revokeTokens())) {
			detail.append(detail.isEmpty() ? "" : ", ").append("tokens revoked");
		}
		this.services.audit().record(principal.username(), AuditService.USER_UPDATE, null, username, Requests.clientIp(ctx, this.services.config()), detail.isEmpty() ? null : detail.toString());
		ctx.json(UserResponse.of(Objects.requireNonNull(this.services.users().get(username)), this.services.users().security(username)));
	}
	
	@OpenApi(
		summary = "Delete a user with its tokens and permissions",
		operationId = "deleteUser",
		path = "/api/users/{username}",
		methods = HttpMethod.DELETE,
		tags = "Users",
		pathParams = @OpenApiParam(name = "username", required = true),
		responses = @OpenApiResponse(status = "204")
	)
	public void deleteUser(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAdmin(ctx);
		String username = ctx.pathParam("username");
		if (principal.username().equals(username)) {
			throw HttpError.badRequest("Can not delete yourself");
		}
		if (this.services.users().get(username) == null) {
			throw HttpError.notFound("No such user: " + username);
		}
		this.services.users().delete(username).forEach(this.services.tokenUsage()::invalidate);
		this.services.auth().invalidateCache();
		this.services.audit().record(principal.username(), AuditService.USER_DELETE, null, username, Requests.clientIp(ctx, this.services.config()), null);
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "List permissions",
		operationId = "listPermissions",
		path = "/api/permissions",
		methods = HttpMethod.GET,
		tags = "Permissions",
		queryParams = {
			@OpenApiParam(name = "repository"),
			@OpenApiParam(name = "username")
		},
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PermissionResponse[].class))
	)
	public void listPermissions(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		ctx.json(this.services.users().listPermissions(ctx.queryParam("repository"), ctx.queryParam("username")).stream()
			.map(permission -> new PermissionResponse(permission.repository(), permission.username(), permission.level()))
			.toList());
	}
	
	@OpenApi(
		summary = "Grant a user an access level (READ, WRITE, DELETE) on a repository, use '*' for all repositories",
		operationId = "setPermission",
		path = "/api/permissions",
		methods = HttpMethod.PUT,
		tags = "Permissions",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = PermissionRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PermissionResponse.class))
	)
	public void setPermission(@NonNull Context ctx) throws Exception {
		Principal admin = this.services.auth().requireAdmin(ctx);
		PermissionRequest request = ctx.bodyAsClass(PermissionRequest.class);
		AccessLevel level = AccessLevel.parse(request.level());
		if (request.repository() == null || request.username() == null || level == null) {
			throw HttpError.badRequest("repository, username and level (READ, WRITE, DELETE) are required");
		}
		if (!"*".equals(request.repository()) && this.services.repositories().get(request.repository()) == null) {
			throw HttpError.notFound("No such repository: " + request.repository());
		}
		if (this.services.users().get(request.username()) == null) {
			throw HttpError.notFound("No such user: " + request.username());
		}
		this.services.users().setPermission(request.repository(), request.username(), level);
		this.services.audit().record(admin.username(), AuditService.PERMISSION_SET, request.repository(), request.username(), Requests.clientIp(ctx, this.services.config()), level.name());
		ctx.json(new PermissionResponse(request.repository(), request.username(), level.name()));
	}
	
	@OpenApi(
		summary = "Revoke the permission of a user on a repository",
		operationId = "deletePermission",
		path = "/api/permissions",
		methods = HttpMethod.DELETE,
		tags = "Permissions",
		queryParams = {
			@OpenApiParam(name = "repository", required = true),
			@OpenApiParam(name = "username", required = true)
		},
		responses = @OpenApiResponse(status = "204")
	)
	public void deletePermission(@NonNull Context ctx) throws Exception {
		Principal admin = this.services.auth().requireAdmin(ctx);
		String repository = ctx.queryParam("repository");
		String username = ctx.queryParam("username");
		if (repository == null || username == null) {
			throw HttpError.badRequest("repository and username are required");
		}
		if (this.services.users().removePermission(repository, username) == 0) {
			throw HttpError.notFound("No such permission");
		}
		this.services.audit().record(admin.username(), AuditService.PERMISSION_DELETE, repository, username, Requests.clientIp(ctx, this.services.config()), null);
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "List the tokens of the caller (admins may pass a username), requires password authentication",
		operationId = "listTokens",
		path = "/api/tokens",
		methods = HttpMethod.GET,
		tags = "Tokens",
		queryParams = @OpenApiParam(name = "username"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = TokenResponse[].class))
	)
	public void listTokens(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requirePassword(ctx, "Managing tokens");
		String username = this.targetUser(principal, ctx.queryParam("username"));
		List<TokenEntity> tokens = this.services.users().listTokens(username);
		Map<String, TokenMetaEntity> meta = this.services.users().tokenMeta(tokens.stream().map(TokenEntity::id).toList());
		ctx.json(tokens.stream().map(token -> TokenResponse.of(token, meta.get(token.id()), null)).toList());
	}
	
	@OpenApi(
		summary = "Create a token, the secret is only returned once, requires password authentication",
		description = "Tokens can be used as password (basic auth), bearer token (npm), api key (NuGet) or raw authorization header (Cargo)",
		operationId = "createToken",
		path = "/api/tokens",
		methods = HttpMethod.POST,
		tags = "Tokens",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreateTokenRequest.class)),
		responses = @OpenApiResponse(status = "201", content = @OpenApiContent(from = TokenResponse.class))
	)
	public void createToken(@NonNull Context ctx) throws Exception {
		// A token must not be able to create tokens, otherwise a leaked short-lived token could be turned into one that never expires
		Principal principal = this.services.auth().requirePassword(ctx, "Managing tokens");
		CreateTokenRequest request = ctx.bodyAsClass(CreateTokenRequest.class);
		String username = Objects.requireNonNull(this.targetUser(principal, request.username()));
		if (this.services.users().get(username) == null) {
			throw HttpError.notFound("No such user: " + username);
		}
		// A caller that forgets the level gets the least access, not the most
		AccessLevel level = request.level() == null ? AccessLevel.READ : AccessLevel.parse(request.level());
		if (level == null) {
			throw HttpError.badRequest("Invalid level, allowed: READ, WRITE, DELETE");
		}
		Instant expiresAt = request.expiresInDays() == null || request.expiresInDays() <= 0 ? null : Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS);
		String name = request.name() == null || request.name().isBlank() ? "token" : request.name().strip();
		
		String allowedIps = request.allowedIps() == null || request.allowedIps().isBlank() ? null : request.allowedIps().strip();
		if (allowedIps != null) {
			if (allowedIps.length() > 2048) {
				throw HttpError.badRequest("Allowed IPs must have at most 2048 characters");
			}
			if (!TokenUsageService.validAllowList(allowedIps)) {
				throw HttpError.badRequest("Allowed IPs must be a comma or whitespace separated list of IPs or CIDR ranges");
			}
		}
		long rateLimit = request.rateLimitPerMinute() == null || request.rateLimitPerMinute() <= 0 ? 0 : request.rateLimitPerMinute();
		
		UserService.CreatedToken token = this.services.users().createToken(username, name.length() > 128 ? name.substring(0, 128) : name, level, expiresAt, allowedIps, rateLimit);
		TokenMetaEntity meta = new TokenMetaEntity(token.token().id(), allowedIps, rateLimit, null, null);
		this.services.audit().record(principal.username(), AuditService.TOKEN_CREATE, null, token.token().id(), Requests.clientIp(ctx, this.services.config()), "user=" + username + ", level=" + level.name());
		ctx.status(201).json(TokenResponse.of(token.token(), meta, token.secret()));
	}
	
	@OpenApi(
		summary = "Revoke a token, requires password authentication",
		operationId = "deleteToken",
		path = "/api/tokens/{id}",
		methods = HttpMethod.DELETE,
		tags = "Tokens",
		pathParams = @OpenApiParam(name = "id", required = true),
		responses = @OpenApiResponse(status = "204")
	)
	public void deleteToken(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requirePassword(ctx, "Managing tokens");
		TokenEntity token = this.services.users().getToken(ctx.pathParam("id"));
		if (token == null || (!principal.isAdmin() && !token.username().equals(principal.username()))) {
			throw HttpError.notFound("No such token");
		}
		this.services.users().deleteToken(token.id());
		this.services.tokenUsage().invalidate(token.id());
		this.services.auth().invalidateCache();
		this.services.audit().record(principal.username(), AuditService.TOKEN_DELETE, null, token.id(), Requests.clientIp(ctx, this.services.config()), "user=" + token.username());
		ctx.status(204);
	}
	
	private String targetUser(@NonNull Principal principal, String requested) {
		if (requested == null || requested.equals(principal.username())) {
			return principal.username();
		}
		if (!principal.isAdmin()) {
			throw HttpError.forbidden("Administrator privileges required");
		}
		return requested;
	}
}
