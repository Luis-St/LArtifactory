package net.luis.artifactory.auth;

import net.luis.artifactory.database.entity.*;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static net.luis.artifactory.database.Tables.*;

public class UserService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);
	
	/**
	 * Prefix of session secrets, sessions are only accepted as bearer token.<br>
	 */
	public static final String SESSION_PREFIX = "las_";
	
	private final SqlDatabase database;
	
	public UserService(@NonNull SqlDatabase database) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
	}
	
	@NonNull
	public static String hashToken(@NonNull String secret) {
		return net.luis.artifactory.storage.BlobStore.sha256Hex(secret);
	}
	
	public void bootstrapAdmin(@NonNull String username, @Nullable String password) throws SqlException {
		if (this.database.from(USERS).select().count() > 0) {
			return;
		}
		
		String effectivePassword = password;
		if (effectivePassword == null) {
			effectivePassword = PasswordHasher.randomSecret(18);
			LOGGER.warn("No admin password configured, generated initial password for user '{}': {}", username, effectivePassword);
		}
		this.create(username, effectivePassword, true);
		LOGGER.info("Created initial admin user '{}'", username);
	}
	
	@NonNull
	public List<UserEntity> list() throws SqlException {
		return this.database.from(USERS).select().orderBy(USER_NAME.ascending()).fetch();
	}
	
	@Nullable
	public UserEntity get(@NonNull String username) throws SqlException {
		return this.database.from(USERS).select().where(Sql.equalTo(USER_NAME, username)).fetchOneOrNull();
	}
	
	@NonNull
	public UserEntity create(@NonNull String username, @NonNull String password, boolean admin) throws SqlException {
		UserEntity user = new UserEntity(username, PasswordHasher.hash(password), admin, Instant.now());
		this.database.from(USERS).insert(user).execute();
		return user;
	}
	
	public void update(@NonNull String username, @Nullable String password, @Nullable Boolean admin) throws SqlException {
		if (password == null && admin == null) {
			return;
		}
		var update = this.database.from(USERS).update();
		if (password != null) {
			update = update.set(USER_PASSWORD_HASH, PasswordHasher.hash(password));
		}
		if (admin != null) {
			update = update.set(USER_ADMIN, admin);
		}
		update.where(Sql.equalTo(USER_NAME, username)).execute();
	}
	
	public long countAdmins() throws SqlException {
		return this.database.from(USERS).select().where(Sql.equalTo(USER_ADMIN, true)).count();
	}
	
	/**
	 * Returns whether a user exists whose name differs from the given one only by case.<br>
	 */
	public boolean existsIgnoreCase(@NonNull String username) throws SqlException {
		return this.database.from(USERS).select().where(Sql.equalTo(Sql.lower(USER_NAME), username.toLowerCase(Locale.ROOT))).exists();
	}
	
	/**
	 * Deletes a user with its tokens, sessions and permissions.<br>
	 *
	 * @return The ids of the deleted tokens
	 */
	@NonNull
	public List<String> delete(@NonNull String username) throws SqlException {
		List<TokenEntity> tokens = this.database.from(TOKENS).select().where(Sql.equalTo(TOKEN_USERNAME, username)).fetch();
		this.database.inTransaction(transaction -> {
			for (TokenEntity token : tokens) {
				transaction.from(TOKEN_META).delete().where(Sql.equalTo(TOKEN_META_ID, token.id())).execute();
			}
			transaction.from(TOKENS).delete().where(Sql.equalTo(TOKEN_USERNAME, username)).execute();
			transaction.from(SESSIONS).delete().where(Sql.equalTo(SESSION_USERNAME, username)).execute();
			transaction.from(PERMISSIONS).delete().where(Sql.equalTo(PERMISSION_USERNAME, username)).execute();
			for (TokenEntity token : tokens) {
				transaction.from(SESSIONS).delete().where(Sql.equalTo(SESSION_TOKEN_ID, token.id())).execute();
			}
			return transaction.from(USERS).delete().where(Sql.equalTo(USER_NAME, username)).execute();
		});
		return tokens.stream().map(TokenEntity::id).toList();
	}
	
	@NonNull
	public List<PermissionEntity> listPermissions(@Nullable String repository, @Nullable String username) throws SqlException {
		List<SqlCondition> conditions = new ArrayList<>();
		if (repository != null) {
			conditions.add(Sql.equalTo(PERMISSION_REPOSITORY, repository));
		}
		if (username != null) {
			conditions.add(Sql.equalTo(PERMISSION_USERNAME, username));
		}
		var query = this.database.from(PERMISSIONS).select();
		if (!conditions.isEmpty()) {
			query = query.where(SqlCondition.allOf(conditions));
		}
		return query.orderBy(PERMISSION_REPOSITORY.ascending(), PERMISSION_USERNAME.ascending()).fetch();
	}
	
	public void setPermission(@NonNull String repository, @NonNull String username, @NonNull AccessLevel level) throws SqlException {
		this.database.inTransaction(transaction -> {
			transaction.from(PERMISSIONS).delete().where(SqlCondition.allOf(Sql.equalTo(PERMISSION_REPOSITORY, repository), Sql.equalTo(PERMISSION_USERNAME, username))).execute();
			return transaction.from(PERMISSIONS).insert(new PermissionEntity(repository, username, level.name())).execute();
		});
	}
	
	public int removePermission(@NonNull String repository, @NonNull String username) throws SqlException {
		return this.database.from(PERMISSIONS).delete().where(SqlCondition.allOf(Sql.equalTo(PERMISSION_REPOSITORY, repository), Sql.equalTo(PERMISSION_USERNAME, username))).execute();
	}
	
	/**
	 * Returns the highest access level the given user has on the repository, considering wildcard ({@code *}) permissions.<br>
	 */
	@Nullable
	public AccessLevel permissionLevel(@NonNull String repository, @NonNull String username) throws SqlException {
		List<PermissionEntity> permissions = this.database.from(PERMISSIONS).select().where(SqlCondition.allOf(
			Sql.equalTo(PERMISSION_USERNAME, username),
			SqlCondition.anyOf(Sql.equalTo(PERMISSION_REPOSITORY, repository), Sql.equalTo(PERMISSION_REPOSITORY, "*"))
		)).fetch();
		
		AccessLevel best = null;
		for (PermissionEntity permission : permissions) {
			AccessLevel level = AccessLevel.parse(permission.level());
			if (level != null && (best == null || level.includes(best))) {
				best = level;
			}
		}
		return best;
	}
	
	@NonNull
	public List<TokenEntity> listTokens(@Nullable String username) throws SqlException {
		var query = this.database.from(TOKENS).select();
		if (username != null) {
			query = query.where(Sql.equalTo(TOKEN_USERNAME, username));
		}
		return query.orderBy(TOKEN_CREATED_AT.ascending()).fetch();
	}
	
	@Nullable
	public TokenEntity getToken(@NonNull String id) throws SqlException {
		return this.database.from(TOKENS).select().where(Sql.equalTo(TOKEN_ID, id)).fetchOneOrNull();
	}
	
	@Nullable
	public TokenEntity findTokenBySecret(@NonNull String secret) throws SqlException {
		return this.database.from(TOKENS).select().where(Sql.equalTo(TOKEN_HASH, hashToken(secret))).fetchOneOrNull();
	}
	
	/**
	 * Creates a new token and returns the plain secret, the secret is not stored and can not be retrieved later.<br>
	 */
	@NonNull
	public CreatedToken createToken(@NonNull String username, @NonNull String name, @NonNull AccessLevel level, @Nullable Instant expiresAt) throws SqlException {
		return this.createToken(username, name, level, expiresAt, null, 0);
	}
	
	/**
	 * Creates a new token together with its {@link TokenMetaEntity settings} (ip allowlist and rate limit) atomically.<br>
	 */
	@NonNull
	public CreatedToken createToken(@NonNull String username, @NonNull String name, @NonNull AccessLevel level, @Nullable Instant expiresAt, @Nullable String allowedIps, long rateLimit) throws SqlException {
		String secret = "lat_" + PasswordHasher.randomSecret(32);
		TokenEntity token = new TokenEntity(UUID.randomUUID().toString(), username, name, hashToken(secret), level.name(), Instant.now(), expiresAt);
		TokenMetaEntity meta = new TokenMetaEntity(token.id(), allowedIps, Math.max(0, rateLimit), null, null);
		this.database.inTransaction(transaction -> {
			transaction.from(TOKENS).insert(token).execute();
			return transaction.from(TOKEN_META).insert(meta).execute();
		});
		return new CreatedToken(token, secret);
	}
	
	/**
	 * Deletes a token with its settings and the sessions it was exchanged for.<br>
	 */
	public int deleteToken(@NonNull String id) throws SqlException {
		return this.database.inTransaction(transaction -> {
			transaction.from(SESSIONS).delete().where(Sql.equalTo(SESSION_TOKEN_ID, id)).execute();
			transaction.from(TOKEN_META).delete().where(Sql.equalTo(TOKEN_META_ID, id)).execute();
			return transaction.from(TOKENS).delete().where(Sql.equalTo(TOKEN_ID, id)).execute();
		});
	}
	
	/**
	 * Deletes all tokens of a user.<br>
	 *
	 * @return The ids of the deleted tokens
	 */
	@NonNull
	public List<String> deleteTokens(@NonNull String username) throws SqlException {
		List<String> ids = this.listTokens(username).stream().map(TokenEntity::id).toList();
		for (String id : ids) {
			this.deleteToken(id);
		}
		return ids;
	}
	
	/**
	 * Deletes the token with the given secret.<br>
	 *
	 * @return The id of the deleted token, null if there is no such token
	 */
	@Nullable
	public String deleteTokenBySecret(@NonNull String secret) throws SqlException {
		TokenEntity token = this.findTokenBySecret(secret);
		if (token == null) {
			return null;
		}
		this.deleteToken(token.id());
		return token.id();
	}
	
	//region Token metadata
	@Nullable
	public TokenMetaEntity getTokenMeta(@NonNull String id) throws SqlException {
		return this.database.from(TOKEN_META).select().where(Sql.equalTo(TOKEN_META_ID, id)).fetchOneOrNull();
	}
	
	@NonNull
	public Map<String, TokenMetaEntity> tokenMeta(@NonNull Collection<String> ids) throws SqlException {
		Map<String, TokenMetaEntity> result = new HashMap<>();
		for (String id : ids) {
			TokenMetaEntity meta = this.getTokenMeta(id);
			if (meta != null) {
				result.put(id, meta);
			}
		}
		return result;
	}
	
	/**
	 * Records that a token was used, replacing the previous usage row for the token.<br>
	 */
	public void recordTokenUsage(@NonNull String id, @NonNull Instant at, @Nullable String ip) throws SqlException {
		this.database.inTransaction(transaction -> {
			transaction.from(TOKEN_META).update().set(TOKEN_META_LAST_USED_AT, at).where(Sql.equalTo(TOKEN_META_ID, id)).execute();
			return transaction.from(TOKEN_META).update().set(TOKEN_META_LAST_USED_IP, ip).where(Sql.equalTo(TOKEN_META_ID, id)).execute();
		});
	}
	//endregion
	
	public int deleteExpiredSessions() throws SqlException {
		return this.database.from(SESSIONS).delete().where(Sql.before(SESSION_EXPIRES_AT, Instant.now())).execute();
	}
	
	/**
	 * Creates a full web ui session and returns the plain secret, only its hash is stored.<br>
	 */
	@NonNull
	public CreatedSession createSession(@NonNull String username, @NonNull Duration lifetime) throws SqlException {
		return this.createSession(username, Instant.now().plus(lifetime), null, null);
	}
	
	/**
	 * Creates a session and returns the plain secret, only its hash is stored.<br>
	 *
	 * @param level The maximum access level of a restricted session, null for a full session
	 * @param tokenId The token the session is exchanged for, the session ends when the token is revoked
	 */
	@NonNull
	public CreatedSession createSession(@NonNull String username, @NonNull Instant expiresAt, @Nullable AccessLevel level, @Nullable String tokenId) throws SqlException {
		String secret = SESSION_PREFIX + PasswordHasher.randomSecret(32);
		Instant now = Instant.now();
		SessionEntity session = new SessionEntity(UUID.randomUUID().toString(), username, hashToken(secret), now, expiresAt, level == null ? null : level.name(), tokenId);
		this.database.from(SESSIONS).insert(session).execute();
		return new CreatedSession(session, secret);
	}
	
	@Nullable
	public SessionEntity findSessionBySecret(@NonNull String secret) throws SqlException {
		return this.database.from(SESSIONS).select().where(Sql.equalTo(SESSION_HASH, hashToken(secret))).fetchOneOrNull();
	}
	
	public int deleteSessionBySecret(@NonNull String secret) throws SqlException {
		return this.database.from(SESSIONS).delete().where(Sql.equalTo(SESSION_HASH, hashToken(secret))).execute();
	}
	
	/**
	 * Deletes all sessions of the user, except the session with the given secret (e.g. the session changing the password).<br>
	 */
	public int deleteSessions(@NonNull String username, @Nullable String keepSecret) throws SqlException {
		SqlCondition condition = Sql.equalTo(SESSION_USERNAME, username);
		if (keepSecret != null) {
			condition = SqlCondition.allOf(condition, Sql.equalTo(SESSION_HASH, hashToken(keepSecret)).not());
		}
		return this.database.from(SESSIONS).delete().where(condition).execute();
	}
	
	public record CreatedToken(@NonNull TokenEntity token, @NonNull String secret) {}
	
	public record CreatedSession(@NonNull SessionEntity session, @NonNull String secret) {}
}
