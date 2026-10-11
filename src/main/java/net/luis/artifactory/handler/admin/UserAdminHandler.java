package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.*;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.dto.request.*;
import net.luis.artifactory.dto.response.*;
import net.luis.artifactory.http.HttpError;
import org.jspecify.annotations.NonNull;

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
	
	//region Users
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
		ctx.json(this.services.users().list().stream().map(UserResponse::of).toList());
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
		this.services.auth().requireAdmin(ctx);
		CreateUserRequest request = ctx.bodyAsClass(CreateUserRequest.class);
		if (request.username() == null || !USERNAME.matcher(request.username()).matches() || "__token__".equals(request.username())) {
			throw HttpError.badRequest("Invalid username");
		}
		validatePassword(request.password());
		if (this.services.users().get(request.username()) != null) {
			throw HttpError.conflict("User " + request.username() + " already exists");
		}
		UserEntity user = this.services.users().create(request.username(), request.password(), Boolean.TRUE.equals(request.admin()));
		ctx.status(201).json(UserResponse.of(user));
	}
	
	@OpenApi(
		summary = "Update a user, users may change their own password",
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
		if (this.services.users().get(username) == null) {
			throw HttpError.notFound("No such user: " + username);
		}
		if (request.password() != null) {
			validatePassword(request.password());
		}
		this.services.users().update(username, request.password(), request.admin());
		this.services.auth().invalidateCache();
		ctx.json(UserResponse.of(Objects.requireNonNull(this.services.users().get(username))));
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
		this.services.users().delete(username);
		this.services.auth().invalidateCache();
		ctx.status(204);
	}
	
	private static void validatePassword(String password) {
		if (password == null || password.length() < 12) {
			throw HttpError.badRequest("Password must have at least 12 characters");
		}
		if (password.length() > 256) {
			throw HttpError.badRequest("Password must have at most 256 characters");
		}
	}
	//endregion
	
	//region Permissions
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
		this.services.auth().requireAdmin(ctx);
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
		this.services.auth().requireAdmin(ctx);
		String repository = ctx.queryParam("repository");
		String username = ctx.queryParam("username");
		if (repository == null || username == null) {
			throw HttpError.badRequest("repository and username are required");
		}
		if (this.services.users().removePermission(repository, username) == 0) {
			throw HttpError.notFound("No such permission");
		}
		ctx.status(204);
	}
	//endregion
	
	//region Tokens
	@OpenApi(
		summary = "List the tokens of the caller (admins may pass a username)",
		operationId = "listTokens",
		path = "/api/tokens",
		methods = HttpMethod.GET,
		tags = "Tokens",
		queryParams = @OpenApiParam(name = "username"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = TokenResponse[].class))
	)
	public void listTokens(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		String username = this.targetUser(principal, ctx.queryParam("username"));
		ctx.json(this.services.users().listTokens(username).stream().map(token -> TokenResponse.of(token, null)).toList());
	}
	
	@OpenApi(
		summary = "Create a token, the secret is only returned once",
		description = "Tokens can be used as password (basic auth), bearer token (npm), api key (NuGet) or raw authorization header (Cargo)",
		operationId = "createToken",
		path = "/api/tokens",
		methods = HttpMethod.POST,
		tags = "Tokens",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreateTokenRequest.class)),
		responses = @OpenApiResponse(status = "201", content = @OpenApiContent(from = TokenResponse.class))
	)
	public void createToken(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		CreateTokenRequest request = ctx.bodyAsClass(CreateTokenRequest.class);
		String username = Objects.requireNonNull(this.targetUser(principal, request.username()));
		if (this.services.users().get(username) == null) {
			throw HttpError.notFound("No such user: " + username);
		}
		AccessLevel level = request.level() == null ? AccessLevel.DELETE : AccessLevel.parse(request.level());
		if (level == null) {
			throw HttpError.badRequest("Invalid level, allowed: READ, WRITE, DELETE");
		}
		if (principal.tokenLevel() != null) {
			level = AccessLevel.min(level, principal.tokenLevel());
		}
		Instant expiresAt = request.expiresInDays() == null || request.expiresInDays() <= 0 ? null : Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS);
		String name = request.name() == null || request.name().isBlank() ? "token" : request.name().strip();
		
		UserService.CreatedToken token = this.services.users().createToken(username, name.length() > 128 ? name.substring(0, 128) : name, level, expiresAt);
		ctx.status(201).json(TokenResponse.of(token.token(), token.secret()));
	}
	
	@OpenApi(
		summary = "Revoke a token",
		operationId = "deleteToken",
		path = "/api/tokens/{id}",
		methods = HttpMethod.DELETE,
		tags = "Tokens",
		pathParams = @OpenApiParam(name = "id", required = true),
		responses = @OpenApiResponse(status = "204")
	)
	public void deleteToken(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		TokenEntity token = this.services.users().getToken(ctx.pathParam("id"));
		if (token == null || (!principal.isAdmin() && !token.username().equals(principal.username()))) {
			throw HttpError.notFound("No such token");
		}
		this.services.users().deleteToken(token.id());
		this.services.auth().invalidateCache();
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
	//endregion
}
