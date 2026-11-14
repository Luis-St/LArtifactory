package net.luis.artifactory.auth;

import io.javalin.http.Context;
import net.luis.artifactory.config.ServerConfig;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.AuditService;
import net.luis.artifactory.storage.BlobStore;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Authenticates requests and checks repository permissions.<br>
 * <p>
 *     Supported credentials:
 * </p>
 * <ul>
 *     <li>{@code Authorization: Basic base64(user:password)} with the password of the user or one of its tokens</li>
 *     <li>{@code Authorization: Basic base64(__token__:token)} (PyPI style)</li>
 *     <li>{@code Authorization: Bearer token} (npm style), also used for web ui sessions ({@code las_...})</li>
 *     <li>{@code Authorization: token} without scheme (Cargo style)</li>
 *     <li>{@code X-NuGet-ApiKey: token} (NuGet style) and {@code X-JFrog-Art-Api: token}</li>
 * </ul>
 */
public class AuthService {
	
	private static final String PRINCIPAL_ATTRIBUTE = "artifactory.principal";
	private static final long CACHE_TTL_MILLIS = 60_000;
	// Bound the number of concurrent (deliberately expensive) password hashes so a flood of authentication attempts
	// can not exhaust CPU, and verify a dummy hash for unknown users to keep the timing independent of user existence.
	private static final Semaphore HASH_SLOTS = new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()));
	private static final long HASH_WAIT_MILLIS = 5_000;
	private static final Duration TICKET_LIFETIME = Duration.ofSeconds(60);
	private static final int MAX_TICKETS = 10_000;
	/**
	 * Bearer token handed out by the registry token endpoint to anonymous clients, it authenticates as anonymous.<br>
	 */
	public static final String ANONYMOUS_TOKEN = "anonymous";
	/**
	 * Query parameter of a download ticket, see {@link #createTicket}.<br>
	 */
	public static final String TICKET_PARAMETER = "ticket";
	private final UserService userService;
	private final TokenUsageService tokenUsage;
	private final ServerConfig config;
	private final LoginThrottle throttle;
	private final AuditService audit;
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
	private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
	private final String dummyHash;
	
	public AuthService(@NonNull UserService userService, @NonNull TokenUsageService tokenUsage, @NonNull ServerConfig config, @NonNull AuditService audit) {
		this.userService = Objects.requireNonNull(userService, "User service must not be null");
		this.tokenUsage = Objects.requireNonNull(tokenUsage, "Token usage service must not be null");
		this.config = Objects.requireNonNull(config, "Config must not be null");
		this.audit = Objects.requireNonNull(audit, "Audit service must not be null");
		this.throttle = new LoginThrottle(config.loginMaxFailures(), Duration.ofMinutes(config.loginLockoutMinutes()));
		this.dummyHash = userService.hasher().hash(PasswordHasher.randomSecret(24));
	}
	
	/**
	 * Returns the user name and password of a basic authorization with a password, null for a token, a session or other credentials.<br>
	 */
	@Nullable
	public static Credentials passwordCredentials(@NonNull Context ctx) {
		String authorization = ctx.header("Authorization");
		if (authorization == null || !authorization.strip().regionMatches(true, 0, "Basic ", 0, 6)) {
			return null;
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(authorization.strip().substring(6).strip()), StandardCharsets.UTF_8);
			int colon = decoded.indexOf(':');
			if (colon < 0) {
				return null;
			}
			String username = decoded.substring(0, colon);
			String password = decoded.substring(colon + 1);
			return "__token__".equals(username) || password.startsWith("lat_") ? null : new Credentials(username, password);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
	
	/**
	 * Returns the session secret of the request, null if the request is not authenticated with a session.<br>
	 */
	@Nullable
	public static String sessionSecret(@NonNull Context ctx) {
		String authorization = ctx.header("Authorization");
		if (authorization == null) {
			return null;
		}
		authorization = authorization.strip();
		if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String value = authorization.substring(7).strip();
			return value.startsWith(UserService.SESSION_PREFIX) ? value : null;
		}
		return null;
	}
	
	/**
	 * Returns the access token secret the request authenticates with, null if it uses a password, a session or no credentials.<br>
	 */
	@Nullable
	public static String tokenSecret(@NonNull Context ctx) {
		String authorization = ctx.header("Authorization");
		if (authorization != null && !authorization.isBlank()) {
			authorization = authorization.strip();
			int space = authorization.indexOf(' ');
			String scheme = space < 0 ? "" : authorization.substring(0, space);
			String value = space < 0 ? authorization : authorization.substring(space + 1).strip();
			if ("Basic".equalsIgnoreCase(scheme)) {
				try {
					String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
					int colon = decoded.indexOf(':');
					String secret = colon < 0 ? "" : decoded.substring(colon + 1);
					return colon >= 0 && ("__token__".equals(decoded.substring(0, colon)) || secret.startsWith("lat_")) ? secret : null;
				} catch (IllegalArgumentException e) {
					return null;
				}
			}
			if (value.startsWith(UserService.SESSION_PREFIX) || ANONYMOUS_TOKEN.equals(value)) {
				return null;
			}
			return "Bearer".equalsIgnoreCase(scheme) || "token".equalsIgnoreCase(scheme) || space < 0 ? value : null;
		}
		for (String header : List.of("X-NuGet-ApiKey", "X-JFrog-Art-Api")) {
			String apiKey = ctx.header(header);
			if (apiKey != null && !apiKey.isBlank()) {
				return apiKey.strip();
			}
		}
		return null;
	}
	
	@NonNull
	private static String describe(@NonNull AccessLevel level) {
		return switch (level) {
			case READ -> "read from";
			case WRITE -> "publish to";
			case DELETE -> "delete from";
		};
	}
	
	@NonNull
	LoginThrottle throttle() {
		return this.throttle;
	}
	
	public void invalidateCache() {
		this.cache.clear();
	}
	
	/**
	 * Removes expired cache entries and login failure counters.<br>
	 */
	public void cleanup() {
		long now = System.currentTimeMillis();
		this.cache.values().removeIf(entry -> entry.expiresAt() <= now);
		this.tickets.values().removeIf(ticket -> ticket.expiresAt().isBefore(Instant.now()));
		this.throttle.cleanup();
	}
	
	/**
	 * Verifies a password against a stored hash while bounding the number of concurrent hash computations.<br>
	 * Password hashing is intentionally expensive, without a bound a flood of authentication attempts could exhaust
	 * the cpu, so this blocks briefly for a slot and returns 503 if none becomes available.<br>
	 */
	public boolean verifyPassword(@NonNull String password, @NonNull String storedHash) {
		boolean acquired;
		try {
			acquired = HASH_SLOTS.tryAcquire(HASH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw HttpError.of(503, "Authentication temporarily unavailable");
		}
		if (!acquired) {
			throw HttpError.of(503, "Authentication temporarily unavailable");
		}
		try {
			return this.userService.hasher().verify(password, storedHash);
		} finally {
			HASH_SLOTS.release();
		}
	}
	
	/**
	 * Checks the password of a user, failed attempts count towards the login throttle of the client.<br>
	 *
	 * @return The user, null if the user does not exist or the password is wrong
	 * @throws HttpError 429 if the client is locked out because of too many failed logins
	 */
	@Nullable
	public UserEntity login(@NonNull Context ctx, @NonNull String username, @NonNull String password) throws SqlException {
		String address = Requests.clientIp(ctx, this.config);
		// Locked out attempts are not recorded one by one, the lockout itself was recorded when it began
		this.throttle.check(address, username);
		
		UserEntity user = this.userService.get(username);
		boolean valid;
		if (user == null) {
			this.verifyPassword(password, this.dummyHash); // keep timing independent of whether the user exists
			valid = false;
		} else {
			valid = this.verifyPassword(password, user.passwordHash());
		}
		if (!valid) {
			boolean locked = this.throttle.failure(address, username);
			this.audit.record(username, AuditService.LOGIN_FAILED, null, username, address, user == null ? "unknown user" : null);
			if (locked) {
				this.audit.record(username, AuditService.LOGIN_LOCKED, null, username, address, "locked for " + this.config.loginLockoutMinutes() + " minutes");
			}
			return null;
		}
		if (!this.userService.security(user.username()).mfaEnabled()) {
			// With two-factor authentication the password is only the first step, the counter is reset once a code completes
			// the sign in, otherwise signing in again with the password would allow guessing codes without limit
			this.throttle.success(address, username);
		}
		if (this.userService.hasher().needsRehash(user.passwordHash())) {
			// Hashes created before the pepper was introduced are replaced while the plain password is at hand
			this.userService.rehash(user.username(), password, user.passwordHash());
		}
		return user;
	}
	
	/**
	 * Authenticates the request, returns null for anonymous requests.<br>
	 *
	 * @throws HttpError 401 if credentials are present but invalid
	 */
	@Nullable
	public Principal authenticate(@NonNull Context ctx) throws SqlException {
		if (ctx.attributeMap().containsKey(PRINCIPAL_ATTRIBUTE)) {
			return ctx.attribute(PRINCIPAL_ATTRIBUTE);
		}
		
		Principal principal = this.resolve(ctx);
		ctx.attribute(PRINCIPAL_ATTRIBUTE, principal);
		if (principal != null) {
			MDC.put(AuditService.USER_KEY, principal.username());
		}
		return principal;
	}
	
	@NonNull
	public Principal requireAuthenticated(@NonNull Context ctx, @NonNull String challenge) throws SqlException {
		Principal principal = this.authenticate(ctx);
		if (principal == null) {
			throw HttpError.unauthorized(challenge);
		}
		return principal;
	}
	
	/**
	 * Requires a principal that signed in with a password or a session, tokens are rejected with 403.<br>
	 */
	@NonNull
	public Principal requirePassword(@NonNull Context ctx, @NonNull String action) throws SqlException {
		Principal principal = this.requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		if (principal.tokenLevel() != null) {
			throw HttpError.forbidden(action + " requires signing in with a password, access tokens can not be used");
		}
		return principal;
	}
	
	@NonNull
	public Principal requireAdmin(@NonNull Context ctx) throws SqlException {
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
	@Nullable
	public Principal authorize(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull AccessLevel required, @NonNull String challenge) throws SqlException {
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
	
	/**
	 * Returns a bearer token for the credentials of the request, used by the token flow of the OCI registry.<br>
	 * An access token or session is returned as is, so its restrictions keep applying. A password is never returned,
	 * a restricted session with the given lifetime is created for it instead: it grants repository access like a token with
	 * level DELETE, but never administration, so a registry credential can not administer the server.<br>
	 *
	 * @return The bearer token, {@link #ANONYMOUS_TOKEN} for an anonymous request
	 * @throws HttpError 401 if credentials are present but invalid
	 */
	@NonNull
	public String bearerToken(@NonNull Context ctx, @NonNull Duration sessionLifetime) throws SqlException {
		Principal principal = this.authenticate(ctx);
		if (principal == null) {
			return ANONYMOUS_TOKEN;
		}
		String authorization = Objects.requireNonNullElse(ctx.header("Authorization"), "").strip();
		int space = authorization.indexOf(' ');
		String scheme = space < 0 ? "" : authorization.substring(0, space);
		String value = space < 0 ? authorization : authorization.substring(space + 1).strip();
		if ("Basic".equalsIgnoreCase(scheme)) {
			String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
			String username = decoded.substring(0, decoded.indexOf(':'));
			String secret = decoded.substring(decoded.indexOf(':') + 1);
			if ("__token__".equals(username) || secret.startsWith("lat_")) {
				return secret;
			}
			return this.userService.createSession(principal.username(), Instant.now().plus(sessionLifetime), AccessLevel.DELETE, null).secret();
		}
		if (!authorization.isEmpty()) {
			return value;
		}
		for (String header : List.of("X-NuGet-ApiKey", "X-JFrog-Art-Api")) {
			String apiKey = ctx.header(header);
			if (apiKey != null && !apiKey.isBlank()) {
				return apiKey.strip();
			}
		}
		throw HttpError.unauthorized("Basic realm=\"artifactory\"");
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
	
	@Nullable
	public AccessLevel effectiveLevel(@NonNull Principal principal, @NonNull String repository) throws SqlException {
		AccessLevel level = principal.admin() ? AccessLevel.DELETE : this.userService.permissionLevel(repository, principal.username());
		if (level != null && principal.tokenLevel() != null) {
			level = AccessLevel.min(level, principal.tokenLevel());
		}
		return level;
	}
	
	@Nullable
	private Principal resolve(@NonNull Context ctx) throws SqlException {
		String authorization = ctx.header("Authorization");
		if (authorization != null && !authorization.isBlank()) {
			authorization = authorization.strip();
			int space = authorization.indexOf(' ');
			String scheme = space < 0 ? "" : authorization.substring(0, space);
			String value = space < 0 ? authorization : authorization.substring(space + 1).strip();
			
			if ("Basic".equalsIgnoreCase(scheme)) {
				return this.basic(ctx, value);
			}
			if ("Bearer".equalsIgnoreCase(scheme) && ANONYMOUS_TOKEN.equals(value)) {
				return null;
			}
			if ("Bearer".equalsIgnoreCase(scheme) && value.startsWith(UserService.SESSION_PREFIX)) {
				return this.requireSession(ctx, value);
			}
			if ("Bearer".equalsIgnoreCase(scheme) || "token".equalsIgnoreCase(scheme)) {
				return this.requireToken(ctx, value, null);
			}
			if (space < 0) {
				return this.requireToken(ctx, value, null); // Cargo sends the raw token
			}
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		
		for (String header : List.of("X-NuGet-ApiKey", "X-JFrog-Art-Api")) {
			String apiKey = ctx.header(header);
			if (apiKey != null && !apiKey.isBlank()) {
				return this.requireToken(ctx, apiKey.strip(), null);
			}
		}
		String ticket = ctx.queryParam(TICKET_PARAMETER);
		if (ticket != null && Requests.isGetOrHead(ctx)) {
			return this.redeemTicket(ctx, ticket);
		}
		return null;
	}
	
	/**
	 * Creates a single use download ticket for one path, so the web ui can download with a plain link that the browser streams to disk
	 * instead of buffering the file in memory to attach the authorization header.<br>
	 * The ticket authenticates one GET of exactly that path within a minute, with at most READ access.<br>
	 *
	 * @param path The raw path of the download, starting with a slash
	 * @return The ticket
	 */
	@NonNull
	public String createTicket(@NonNull Principal principal, @NonNull String path) {
		if (this.tickets.size() >= MAX_TICKETS) {
			this.cleanup();
			if (this.tickets.size() >= MAX_TICKETS) {
				throw HttpError.of(503, "Too many pending downloads, try again later");
			}
		}
		String ticket = PasswordHasher.randomSecret(24);
		Principal reader = new Principal(principal.username(), principal.admin(), principal.tokenLevel() == null ? AccessLevel.READ : AccessLevel.min(principal.tokenLevel(), AccessLevel.READ));
		this.tickets.put(ticket, new Ticket(reader, path, Instant.now().plus(TICKET_LIFETIME)));
		return ticket;
	}
	
	@NonNull
	private Principal redeemTicket(@NonNull Context ctx, @NonNull String value) {
		Ticket ticket = this.tickets.remove(value);
		if (ticket == null || ticket.expiresAt().isBefore(Instant.now()) || !ticket.path().equals(Requests.rawPath(ctx))) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		return ticket.principal();
	}
	
	@NonNull
	private Principal basic(@NonNull Context ctx, @NonNull String encoded) throws SqlException {
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
			return this.requireToken(ctx, secret, "__token__".equals(username) ? null : username);
		}
		
		String cacheKey = "basic:" + BlobStore.sha256Hex(decoded);
		CacheEntry cached = this.cache.get(cacheKey);
		if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
			return cached.principal();
		}
		
		UserEntity user = this.login(ctx, username, secret);
		if (user == null) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		if (this.userService.security(user.username()).mfaEnabled()) {
			// A password alone is not enough with two-factor authentication, clients use an access token instead
			throw HttpError.mfaRequired();
		}
		Principal principal = new Principal(user.username(), user.admin(), null);
		this.cache.put(cacheKey, new CacheEntry(principal, System.currentTimeMillis() + CACHE_TTL_MILLIS, null, null));
		return principal;
	}
	
	@NonNull
	private Principal requireToken(@NonNull Context ctx, @NonNull String secret, @Nullable String expectedUser) throws SqlException {
		String cacheKey = "token:" + UserService.hashToken(secret);
		CacheEntry cached = this.cached(cacheKey);
		Principal principal;
		String tokenId;
		if (cached != null) {
			principal = cached.principal();
			tokenId = Objects.requireNonNull(cached.tokenId(), "Token cache entry must have a token id");
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
			tokenId = token.id();
			this.cache.put(cacheKey, new CacheEntry(principal, System.currentTimeMillis() + CACHE_TTL_MILLIS, token.expiresAt(), token.id()));
		}
		
		if (expectedUser != null && !expectedUser.equals(principal.username())) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		// Enforce the token ip allowlist and rate limit on every request (also on cache hits), and record the usage
		this.tokenUsage.enforceAndRecord(tokenId, Requests.clientIp(ctx, this.config));
		return principal;
	}
	
	@NonNull
	private Principal requireSession(@NonNull Context ctx, @NonNull String secret) throws SqlException {
		String cacheKey = "session:" + UserService.hashToken(secret);
		CacheEntry cached = this.cached(cacheKey);
		if (cached != null) {
			if (cached.tokenId() != null) {
				this.tokenUsage.enforceAndRecord(cached.tokenId(), Requests.clientIp(ctx, this.config));
			}
			return cached.principal();
		}
		SessionEntity session = this.userService.findSessionBySecret(secret);
		if (session == null || session.expiresAt().isBefore(Instant.now())) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		UserEntity user = this.userService.get(session.username());
		if (user == null) {
			throw HttpError.unauthorized("Basic realm=\"artifactory\"");
		}
		// A restricted session (exchanged for a token or created by the registry token flow) acts with the level of a token
		AccessLevel level = session.level() == null ? null : Objects.requireNonNullElse(AccessLevel.parse(session.level()), AccessLevel.READ);
		Principal principal = new Principal(user.username(), user.admin(), level);
		this.cache.put(cacheKey, new CacheEntry(principal, System.currentTimeMillis() + CACHE_TTL_MILLIS, session.expiresAt(), session.tokenId()));
		if (session.tokenId() != null) {
			// The ip allowlist and rate limit of the token keep applying to the session
			this.tokenUsage.enforceAndRecord(session.tokenId(), Requests.clientIp(ctx, this.config));
		}
		return principal;
	}
	
	/**
	 * Returns the cached entry, entries are re-checked against the expiry of their token or session so a credential that
	 * expires within the cache window stops working immediately.<br>
	 */
	@Nullable
	private CacheEntry cached(@NonNull String cacheKey) {
		CacheEntry cached = this.cache.get(cacheKey);
		if (cached != null && cached.expiresAt() > System.currentTimeMillis() && (cached.credentialExpiresAt() == null || cached.credentialExpiresAt().isAfter(Instant.now()))) {
			return cached;
		}
		return null;
	}
	
	public record Credentials(@NonNull String username, @NonNull String password) {}
	
	private record Ticket(@NonNull Principal principal, @NonNull String path, @NonNull Instant expiresAt) {}
	
	private record CacheEntry(@NonNull Principal principal, long expiresAt, @Nullable Instant credentialExpiresAt, @Nullable String tokenId) {}
}
