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
		}, (builder, _) -> builder.dropTable(SESSIONS))
	);
	
	private Migrations() {}
	
	private static @NonNull SqlMigration migration(@NonNull Version version, @NonNull String description, @NonNull Step up, @NonNull Step down) {
		return new SqlMigration() {
			@Override
			public @NonNull Version version() {
				return version;
			}
			
			@Override
			public @NonNull String description() {
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
