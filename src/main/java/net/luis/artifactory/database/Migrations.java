package net.luis.artifactory.database;

import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.index.SqlIndexMethod;
import net.luis.utils.io.database.migration.*;
import net.luis.utils.io.database.type.SqlTypes;
import net.luis.utils.io.database.type.parameter.SqlParameter;
import net.luis.utils.util.Version;
import org.jspecify.annotations.NonNull;

import java.util.List;

import static net.luis.artifactory.database.Tables.*;

/**
 * Schema changes applied on top of the {@link Tables#BASELINE baseline} tables, in version order.<br>
 * Applied migrations are recorded in the database, a migration must never be changed once it was released, add a new one instead.<br>
 */
public final class Migrations {
	
	public static final List<SqlMigration> ALL = List.of(
		migration(Version.of(1, 1, 0), "Indexes for blob references, package lookups and tokens", (builder, _) -> {
			builder.createIndex(FILES, "idx_files_sha256", index -> index.columns(FILE_SHA256).method(SqlIndexMethod.BTREE));
			builder.createIndex(FILES, "idx_files_package_version", index -> index.columns(FILE_REPOSITORY, FILE_PACKAGE, FILE_VERSION).method(SqlIndexMethod.BTREE));
			builder.createIndex(TOKENS, "idx_tokens_username", index -> index.columns(TOKEN_USERNAME).method(SqlIndexMethod.BTREE));
			builder.createIndex(PERMISSIONS, "idx_permissions_username", index -> index.columns(PERMISSION_USERNAME).method(SqlIndexMethod.BTREE));
		}, (builder, _) -> {
			builder.dropIndex(PERMISSIONS, "idx_permissions_username");
			builder.dropIndex(TOKENS, "idx_tokens_username");
			builder.dropIndex(FILES, "idx_files_package_version");
			builder.dropIndex(FILES, "idx_files_sha256");
		}),
		migration(Version.of(1, 2, 0), "Web ui sessions", (builder, _) -> {
			builder.createTable(SESSIONS, table -> table
				.column(SESSION_ID, SqlTypes.STRING.configure(SqlParameter.length(36)), SqlMigrationColumnBuilder::notNull)
				.column(SESSION_USERNAME, SqlTypes.STRING.configure(SqlParameter.length(128)), SqlMigrationColumnBuilder::notNull)
				.column(SESSION_HASH, SqlTypes.STRING.configure(SqlParameter.length(64)), column -> column.notNull().unique())
				.column(SESSION_CREATED_AT, SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), SqlMigrationColumnBuilder::notNull)
				.column(SESSION_EXPIRES_AT, SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), SqlMigrationColumnBuilder::notNull)
				.primaryKey(SESSION_ID)
			);
			builder.createIndex(SESSIONS, "idx_sessions_username", index -> index.columns(SESSION_USERNAME).method(SqlIndexMethod.BTREE));
		}, (builder, _) -> builder.dropTable(SESSIONS)),
		migration(Version.of(1, 3, 0), "Token metadata, audit log, webhooks, remote and virtual repositories", (builder, _) -> {
			builder.createTable(TOKEN_META, table -> table
				.column(TOKEN_META_ID, SqlTypes.STRING.configure(SqlParameter.length(36)), SqlMigrationColumnBuilder::notNull)
				.column(TOKEN_META_ALLOWED_IPS, SqlTypes.TEXT)
				.column(TOKEN_META_RATE_LIMIT, SqlTypes.LONG, SqlMigrationColumnBuilder::notNull)
				.column(TOKEN_META_LAST_USED_AT, SqlTypes.INSTANT.configure(SqlParameter.fractional(6)))
				.column(TOKEN_META_LAST_USED_IP, SqlTypes.STRING.configure(SqlParameter.length(64)))
				.primaryKey(TOKEN_META_ID)
			);
			builder.createTable(AUDIT_LOG, table -> table
				.column(AUDIT_ID, SqlTypes.STRING.configure(SqlParameter.length(36)), SqlMigrationColumnBuilder::notNull)
				.column(AUDIT_TIMESTAMP, SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), SqlMigrationColumnBuilder::notNull)
				.column(AUDIT_ACTOR, SqlTypes.STRING.configure(SqlParameter.length(128)))
				.column(AUDIT_ACTION, SqlTypes.STRING.configure(SqlParameter.length(64)), SqlMigrationColumnBuilder::notNull)
				.column(AUDIT_REPOSITORY, SqlTypes.STRING.configure(SqlParameter.length(64)))
				.column(AUDIT_TARGET, SqlTypes.STRING.configure(SqlParameter.length(1024)))
				.column(AUDIT_IP, SqlTypes.STRING.configure(SqlParameter.length(64)))
				.column(AUDIT_DETAIL, SqlTypes.TEXT)
				.primaryKey(AUDIT_ID)
			);
			builder.createIndex(AUDIT_LOG, "idx_audit_log_timestamp", index -> index.columns(AUDIT_TIMESTAMP).method(SqlIndexMethod.BTREE));
			builder.createTable(WEBHOOKS, table -> table
				.column(WEBHOOK_ID, SqlTypes.STRING.configure(SqlParameter.length(36)), SqlMigrationColumnBuilder::notNull)
				.column(WEBHOOK_REPOSITORY, SqlTypes.STRING.configure(SqlParameter.length(64)))
				.column(WEBHOOK_URL, SqlTypes.STRING.configure(SqlParameter.length(1024)), SqlMigrationColumnBuilder::notNull)
				.column(WEBHOOK_SECRET, SqlTypes.STRING.configure(SqlParameter.length(256)))
				.column(WEBHOOK_EVENTS, SqlTypes.TEXT)
				.column(WEBHOOK_ACTIVE, SqlTypes.BOOLEAN, SqlMigrationColumnBuilder::notNull)
				.column(WEBHOOK_CREATED_AT, SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), SqlMigrationColumnBuilder::notNull)
				.column(WEBHOOK_CREATED_BY, SqlTypes.STRING.configure(SqlParameter.length(128)))
				.primaryKey(WEBHOOK_ID)
			);
			builder.createTable(WEBHOOK_DELIVERIES, table -> table
				.column(DELIVERY_ID, SqlTypes.STRING.configure(SqlParameter.length(36)), SqlMigrationColumnBuilder::notNull)
				.column(DELIVERY_WEBHOOK, SqlTypes.STRING.configure(SqlParameter.length(36)), SqlMigrationColumnBuilder::notNull)
				.column(DELIVERY_EVENT, SqlTypes.STRING.configure(SqlParameter.length(64)), SqlMigrationColumnBuilder::notNull)
				.column(DELIVERY_REPOSITORY, SqlTypes.STRING.configure(SqlParameter.length(64)))
				.column(DELIVERY_SUCCESS, SqlTypes.BOOLEAN, SqlMigrationColumnBuilder::notNull)
				.column(DELIVERY_STATUS, SqlTypes.LONG, SqlMigrationColumnBuilder::notNull)
				.column(DELIVERY_ERROR, SqlTypes.TEXT)
				.column(DELIVERY_CREATED_AT, SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), SqlMigrationColumnBuilder::notNull)
				.primaryKey(DELIVERY_ID)
			);
			builder.createIndex(WEBHOOK_DELIVERIES, "idx_webhook_deliveries_webhook", index -> index.columns(DELIVERY_WEBHOOK).method(SqlIndexMethod.BTREE));
			builder.createTable(REMOTE_REPOSITORIES, table -> table
				.column(REMOTE_REPOSITORY, SqlTypes.STRING.configure(SqlParameter.length(64)), SqlMigrationColumnBuilder::notNull)
				.column(REMOTE_URL, SqlTypes.STRING.configure(SqlParameter.length(1024)), SqlMigrationColumnBuilder::notNull)
				.column(REMOTE_CACHE_ENABLED, SqlTypes.BOOLEAN, SqlMigrationColumnBuilder::notNull)
				.column(REMOTE_USERNAME, SqlTypes.STRING.configure(SqlParameter.length(256)))
				.column(REMOTE_PASSWORD, SqlTypes.STRING.configure(SqlParameter.length(512)))
				.primaryKey(REMOTE_REPOSITORY)
			);
			builder.createTable(VIRTUAL_MEMBERS, table -> table
				.column(VIRTUAL_REPOSITORY, SqlTypes.STRING.configure(SqlParameter.length(64)), SqlMigrationColumnBuilder::notNull)
				.column(VIRTUAL_MEMBER, SqlTypes.STRING.configure(SqlParameter.length(64)), SqlMigrationColumnBuilder::notNull)
				.column(VIRTUAL_POSITION, SqlTypes.LONG, SqlMigrationColumnBuilder::notNull)
				.primaryKey(VIRTUAL_REPOSITORY, VIRTUAL_MEMBER)
			);
		}, (builder, _) -> {
			builder.dropTable(VIRTUAL_MEMBERS);
			builder.dropTable(REMOTE_REPOSITORIES);
			builder.dropTable(WEBHOOK_DELIVERIES);
			builder.dropTable(WEBHOOKS);
			builder.dropTable(AUDIT_LOG);
			builder.dropTable(TOKEN_META);
		}),
		migration(Version.of(1, 4, 0), "Stored repository classes, restricted sessions and audit indexes", (builder, _) -> {
			// Existing repositories get their class from their remote configuration or members on startup, see RepositoryService#backfillKinds
			builder.createTable(REPOSITORY_KINDS, table -> table
				.column(KIND_REPOSITORY, SqlTypes.STRING.configure(SqlParameter.length(64)), SqlMigrationColumnBuilder::notNull)
				.column(KIND_KIND, SqlTypes.STRING.configure(SqlParameter.length(16)), SqlMigrationColumnBuilder::notNull)
				.primaryKey(KIND_REPOSITORY)
			);
			builder.addColumn(SESSION_LEVEL, SqlTypes.STRING.configure(SqlParameter.length(16)));
			builder.addColumn(SESSION_TOKEN_ID, SqlTypes.STRING.configure(SqlParameter.length(36)));
			builder.createIndex(SESSIONS, "idx_sessions_token", index -> index.columns(SESSION_TOKEN_ID).method(SqlIndexMethod.BTREE));
			builder.createIndex(AUDIT_LOG, "idx_audit_log_actor", index -> index.columns(AUDIT_ACTOR).method(SqlIndexMethod.BTREE));
			builder.createIndex(AUDIT_LOG, "idx_audit_log_repository", index -> index.columns(AUDIT_REPOSITORY).method(SqlIndexMethod.BTREE));
		}, (builder, _) -> {
			builder.dropIndex(AUDIT_LOG, "idx_audit_log_repository");
			builder.dropIndex(AUDIT_LOG, "idx_audit_log_actor");
			builder.dropIndex(SESSIONS, "idx_sessions_token");
			builder.dropColumn(SESSION_TOKEN_ID);
			builder.dropColumn(SESSION_LEVEL);
			builder.dropTable(REPOSITORY_KINDS);
		}),
		migration(Version.of(1, 5, 0), "Email addresses and two-factor authentication", (builder, _) -> {
			// A separate table instead of new user columns, the baseline creates the users table with all of its current columns
			builder.createTable(USER_SECURITY, table -> table
				.column(SECURITY_USERNAME, SqlTypes.STRING.configure(SqlParameter.length(128)), SqlMigrationColumnBuilder::notNull)
				.column(SECURITY_EMAIL, SqlTypes.STRING.configure(SqlParameter.length(320)), column -> column.unique())
				.column(SECURITY_TOTP_SECRET, SqlTypes.STRING.configure(SqlParameter.length(256)))
				.column(SECURITY_TOTP_ENABLED, SqlTypes.BOOLEAN, SqlMigrationColumnBuilder::notNull)
				.column(SECURITY_EMAIL_MFA_ENABLED, SqlTypes.BOOLEAN, SqlMigrationColumnBuilder::notNull)
				.column(SECURITY_TOTP_LAST_STEP, SqlTypes.LONG, SqlMigrationColumnBuilder::notNull)
				.column(SECURITY_RECOVERY_CODES, SqlTypes.TEXT)
				.primaryKey(SECURITY_USERNAME)
			);
		}, (builder, _) -> builder.dropTable(USER_SECURITY))
	);
	
	private Migrations() {}
	
	@NonNull
	private static SqlMigration migration(@NonNull Version version, @NonNull String description, @NonNull Step up, @NonNull Step down) {
		return new SqlMigration() {
			@Override
			@NonNull
			public Version version() {
				return version;
			}
			
			@Override
			@NonNull
			public String description() {
				return description;
			}
			
			@Override
			public void up(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
				up.apply(builder, schema);
			}
			
			@Override
			public void down(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException {
				down.apply(builder, schema);
			}
		};
	}
	
	@FunctionalInterface
	private interface Step {
		
		void apply(@NonNull SqlMigrationBuilder builder, @NonNull SqlMigrationSchema schema) throws SqlException;
	}
}
