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

import java.time.Instant;
import java.util.*;

import static net.luis.artifactory.database.Tables.*;

public class UserService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);
	
	private final SqlDatabase database;
	
	public UserService(@NonNull SqlDatabase database) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
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
	
	public @NonNull List<UserEntity> list() throws SqlException {
		return this.database.from(USERS).select().orderBy(USER_NAME.ascending()).fetch();
	}
	
	public @Nullable UserEntity get(@NonNull String username) throws SqlException {
		return this.database.from(USERS).select().where(Sql.equalTo(USER_NAME, username)).fetchOneOrNull();
	}
	
	public @NonNull UserEntity create(@NonNull String username, @NonNull String password, boolean admin) throws SqlException {
		UserEntity user = new UserEntity(username, PasswordHasher.hash(password), admin, Instant.now());
		this.database.from(USERS).insert(user).execute();
		return user;
	}
	
	public void update(@NonNull String username, @Nullable String password, @Nullable Boolean admin) throws SqlException {
		if (password != null) {
			this.database.from(USERS).update().set(USER_PASSWORD_HASH, PasswordHasher.hash(password)).where(Sql.equalTo(USER_NAME, username)).execute();
		}
		if (admin != null) {
			this.database.from(USERS).update().set(USER_ADMIN, admin).where(Sql.equalTo(USER_NAME, username)).execute();
		}
	}
	
	public void delete(@NonNull String username) throws SqlException {
		this.database.inTransaction(transaction -> {
			transaction.from(TOKENS).delete().where(Sql.equalTo(TOKEN_USERNAME, username)).execute();
			transaction.from(PERMISSIONS).delete().where(Sql.equalTo(PERMISSION_USERNAME, username)).execute();
			return transaction.from(USERS).delete().where(Sql.equalTo(USER_NAME, username)).execute();
		});
	}
	
	//region Permissions
	public @NonNull List<PermissionEntity> listPermissions(@Nullable String repository, @Nullable String username) throws SqlException {
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
	public @Nullable AccessLevel permissionLevel(@NonNull String repository, @NonNull String username) throws SqlException {
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
	//endregion
	
	//region Tokens
	public @NonNull List<TokenEntity> listTokens(@Nullable String username) throws SqlException {
		var query = this.database.from(TOKENS).select();
		if (username != null) {
			query = query.where(Sql.equalTo(TOKEN_USERNAME, username));
		}
		return query.orderBy(TOKEN_CREATED_AT.ascending()).fetch();
	}
	
	public @Nullable TokenEntity getToken(@NonNull String id) throws SqlException {
		return this.database.from(TOKENS).select().where(Sql.equalTo(TOKEN_ID, id)).fetchOneOrNull();
	}
	
	public @Nullable TokenEntity findTokenBySecret(@NonNull String secret) throws SqlException {
		return this.database.from(TOKENS).select().where(Sql.equalTo(TOKEN_HASH, hashToken(secret))).fetchOneOrNull();
	}
	
	/**
	 * Creates a new token and returns the plain secret, the secret is not stored and can not be retrieved later.<br>
	 */
	public @NonNull CreatedToken createToken(@NonNull String username, @NonNull String name, @NonNull AccessLevel level, @Nullable Instant expiresAt) throws SqlException {
		String secret = "lat_" + PasswordHasher.randomSecret(32);
		TokenEntity token = new TokenEntity(UUID.randomUUID().toString(), username, name, hashToken(secret), level.name(), Instant.now(), expiresAt);
		this.database.from(TOKENS).insert(token).execute();
		return new CreatedToken(token, secret);
	}
	
	public int deleteToken(@NonNull String id) throws SqlException {
		return this.database.from(TOKENS).delete().where(Sql.equalTo(TOKEN_ID, id)).execute();
	}
	
	public int deleteTokenBySecret(@NonNull String secret) throws SqlException {
		return this.database.from(TOKENS).delete().where(Sql.equalTo(TOKEN_HASH, hashToken(secret))).execute();
	}
	
	public static @NonNull String hashToken(@NonNull String secret) {
		return net.luis.artifactory.storage.BlobStore.sha256Hex(secret);
	}
	
	public record CreatedToken(@NonNull TokenEntity token, @NonNull String secret) {}
	//endregion
}
