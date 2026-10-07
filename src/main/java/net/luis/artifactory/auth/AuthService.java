package net.luis.artifactory.auth;

import io.javalin.http.Context;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.storage.BlobStore;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authenticates requests and checks repository permissions.<br>
 * <p>
 *     Supported credentials:
 * </p>
 * <ul>
 *     <li>{@code Authorization: Basic base64(user:password)} with the password of the user or one of its tokens</li>
 *     <li>{@code Authorization: Basic base64(__token__:token)} (PyPI style)</li>
 *     <li>{@code Authorization: Bearer token} (npm style)</li>
 *     <li>{@code Authorization: token} without scheme (Cargo style)</li>
 *     <li>{@code X-NuGet-ApiKey: token} (NuGet style) and {@code X-JFrog-Art-Api: token}</li>
 * </ul>
 */
public class AuthService {
	
	private static final String PRINCIPAL_ATTRIBUTE = "artifactory.principal";
	private static final long CACHE_TTL_MILLIS = 60_000;
	
	private final UserService userService;
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
	
	public AuthService(@NonNull UserService userService) {
		this.userService = Objects.requireNonNull(userService, "User service must not be null");
	}
	
	public void invalidateCache() {
		this.cache.clear();
	}
	
	/**
	 * Authenticates the request, returns null for anonymous requests.<br>
	 *
	 * @throws HttpError 401 if credentials are present but invalid
	 */
	public @Nullable Principal authenticate(@NonNull Context ctx) throws SqlException {
		if (ctx.attributeMap().containsKey(PRINCIPAL_ATTRIBUTE)) {
			return ctx.attribute(PRINCIPAL_ATTRIBUTE);
		}
		
		Principal principal = this.resolve(ctx);
		ctx.attribute(PRINCIPAL_ATTRIBUTE, principal);
		return principal;
	}
	
	public @NonNull Principal requireAuthenticated(@NonNull Context ctx, @NonNull String challenge) throws SqlException {
		Principal principal = this.authenticate(ctx);
		if (principal == null) {
			throw HttpError.unauthorized(challenge);
		}
		return principal;
	}
	
	public @NonNull Principal requireAdmin(@NonNull Context ctx) throws SqlException {
		Principal principal = this.requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		if (!principal.isAdmin()) {
			throw HttpError.forbidden("Administrator privileges required");
		}
		return principal;
	}
	
	/**
	 * Checks that the request has the given access level on the repository.<br>
	 *
	 * @return The principal of the request, null if anonymous read access was granted
	 * @throws HttpError 401 if the request is anonymous and access is denied, 403 if the user lacks the permission
	 */
	public @Nullable Principal authorize(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull AccessLevel required, @NonNull String challenge) throws SqlException {
		Principal principal = this.authenticate(ctx);
		if (required == AccessLevel.READ && repository.publicRead()) {
			return principal;
		}
		if (principal == null) {
			throw HttpError.unauthorized(challenge);
		}
		
		AccessLevel level = this.effectiveLevel(principal, repository.name());
		if (level == null || !level.includes(required)) {
			throw HttpError.forbidden("Token not permitted to " + describe(required) + " repository " + repository.name());
		}
		return principal;
	}
	
	public boolean canRead(@Nullable Principal principal, @NonNull RepositoryEntity repository) throws SqlException {
		if (repository.publicRead()) {
			return true;
		}
		if (principal == null) {
			return false;
		}
		return this.effectiveLevel(principal, repository.name()) != null;
	}
	
	public @Nullable AccessLevel effectiveLevel(@NonNull Principal principal, @NonNull String repository) throws SqlException {
		AccessLevel level = principal.admin() ? AccessLevel.DELETE : this.userService.permissionLevel(repository, principal.username());
		if (level != null && principal.tokenLevel() != null) {
			level = AccessLevel.min(level, principal.tokenLevel());
		}
		return level;
	}
	
	private static @NonNull String describe(@NonNull AccessLevel level) {
		return switch (level) {
			case READ -> "read from";
			case WRITE -> "publish to";
			case DELETE -> "delete from";
		};
	}
	
	private @Nullable Principal resolve(@NonNull Context ctx) throws SqlException {
		String authorization = ctx.header("Authorization");
		if (authorization != null && !authorization.isBlank()) {
			authorization = authorization.strip();
			int space = authorization.indexOf(' ');
			String scheme = space < 0 ? "" : authorization.substring(0, space);
			String value = space < 0 ? authorization : authorization.substring(space + 1).strip();
			
			if ("Basic".equalsIgnoreCase(scheme)) {
				return this.basic(value);
			}
			if ("Bearer".equalsIgnoreCase(scheme) || "token".equalsIgnoreCase(scheme)) {
				return this.requireToken(value, null);
			}
			if (space < 0) {
				return this.requireToken(value, null); // Cargo sends the raw token
			}
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		
		for (String header : List.of("X-NuGet-ApiKey", "X-JFrog-Art-Api")) {
			String apiKey = ctx.header(header);
			if (apiKey != null && !apiKey.isBlank()) {
				return this.requireToken(apiKey.strip(), null);
			}
		}
		return null;
	}
	
	private @NonNull Principal basic(@NonNull String encoded) throws SqlException {
		String decoded;
		try {
			decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		int colon = decoded.indexOf(':');
		if (colon < 0) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		String username = decoded.substring(0, colon);
		String secret = decoded.substring(colon + 1);
		
		if ("__token__".equals(username) || secret.startsWith("lat_")) {
			return this.requireToken(secret, "__token__".equals(username) ? null : username);
		}
		
		String cacheKey = "basic:" + BlobStore.sha256Hex(decoded);
		CacheEntry cached = this.cache.get(cacheKey);
		if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
			return cached.principal();
		}
		
		UserEntity user = this.userService.get(username);
		if (user == null || !PasswordHasher.verify(secret, user.passwordHash())) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		Principal principal = new Principal(user.username(), user.admin(), null);
		this.cache.put(cacheKey, new CacheEntry(principal, System.currentTimeMillis() + CACHE_TTL_MILLIS));
		return principal;
	}
	
	private @NonNull Principal requireToken(@NonNull String secret, @Nullable String expectedUser) throws SqlException {
		String cacheKey = "token:" + UserService.hashToken(secret);
		CacheEntry cached = this.cache.get(cacheKey);
		Principal principal;
		if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
			principal = cached.principal();
		} else {
			TokenEntity token = this.userService.findTokenBySecret(secret);
			if (token == null || (token.expiresAt() != null && token.expiresAt().isBefore(Instant.now()))) {
				throw HttpError.unauthorized("Basic realm=\"artifactory\"");
			}
			UserEntity user = this.userService.get(token.username());
			if (user == null) {
				throw HttpError.unauthorized("Basic realm=\"artifactory\"");
			}
			AccessLevel level = Objects.requireNonNullElse(AccessLevel.parse(token.level()), AccessLevel.READ);
			principal = new Principal(user.username(), user.admin(), level);
			// Never cache a token past its expiry, otherwise an expired token would keep working until the cache entry expires.
			long expiresAt = System.currentTimeMillis() + CACHE_TTL_MILLIS;
			if (token.expiresAt() != null) {
				expiresAt = Math.min(expiresAt, token.expiresAt().toEpochMilli());
			}
			this.cache.put(cacheKey, new CacheEntry(principal, expiresAt));
		}
		
		if (expectedUser != null && !expectedUser.equals(principal.username())) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		return principal;
	}
	
	private record CacheEntry(@NonNull Principal principal, long expiresAt) {}
}
