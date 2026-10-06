package net.luis.artifactory.database;

import net.luis.artifactory.database.entity.*;
import net.luis.utils.io.database.table.*;
import net.luis.utils.io.database.type.parameter.SqlParameter;
import net.luis.utils.io.database.type.SqlTypes;

import java.time.Instant;
import java.util.List;

public final class Tables {
	
	//region Users
	public static final SqlTable<UserEntity> USERS = SqlTable.create(UserEntity.class, "users");
	public static final SqlColumn<UserEntity, String> USER_NAME = USERS.column("username", SqlTypes.STRING.configure(SqlParameter.length(128)), UserEntity::username, col -> col.primaryKey().notNull());
	public static final SqlColumn<UserEntity, String> USER_PASSWORD_HASH = USERS.column("password_hash", SqlTypes.STRING.configure(SqlParameter.length(256)), UserEntity::passwordHash, SqlColumnBuilder::notNull);
	public static final SqlColumn<UserEntity, Boolean> USER_ADMIN = USERS.column("admin", SqlTypes.BOOLEAN, UserEntity::admin, SqlColumnBuilder::notNull);
	public static final SqlColumn<UserEntity, Instant> USER_CREATED_AT = USERS.column("created_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), UserEntity::createdAt, SqlColumnBuilder::notNull);
	//endregion
	
	//region Tokens
	public static final SqlTable<TokenEntity> TOKENS = SqlTable.create(TokenEntity.class, "tokens");
	public static final SqlColumn<TokenEntity, String> TOKEN_ID = TOKENS.column("id", SqlTypes.STRING.configure(SqlParameter.length(36)), TokenEntity::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<TokenEntity, String> TOKEN_USERNAME = TOKENS.column("username", SqlTypes.STRING.configure(SqlParameter.length(128)), TokenEntity::username, SqlColumnBuilder::notNull);
	public static final SqlColumn<TokenEntity, String> TOKEN_NAME = TOKENS.column("name", SqlTypes.STRING.configure(SqlParameter.length(128)), TokenEntity::name, SqlColumnBuilder::notNull);
	public static final SqlColumn<TokenEntity, String> TOKEN_HASH = TOKENS.column("token_hash", SqlTypes.STRING.configure(SqlParameter.length(64)), TokenEntity::tokenHash, col -> col.notNull().unique());
	public static final SqlColumn<TokenEntity, String> TOKEN_LEVEL = TOKENS.column("level", SqlTypes.STRING.configure(SqlParameter.length(16)), TokenEntity::level, SqlColumnBuilder::notNull);
	public static final SqlColumn<TokenEntity, Instant> TOKEN_CREATED_AT = TOKENS.column("created_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), TokenEntity::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<TokenEntity, Instant> TOKEN_EXPIRES_AT = TOKENS.column("expires_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), TokenEntity::expiresAt);
	//endregion
	
	//region Repositories
	public static final SqlTable<RepositoryEntity> REPOSITORIES = SqlTable.create(RepositoryEntity.class, "repositories");
	public static final SqlColumn<RepositoryEntity, String> REPOSITORY_NAME = REPOSITORIES.column("name", SqlTypes.STRING.configure(SqlParameter.length(64)), RepositoryEntity::name, col -> col.primaryKey().notNull());
	public static final SqlColumn<RepositoryEntity, String> REPOSITORY_TYPE = REPOSITORIES.column("type", SqlTypes.STRING.configure(SqlParameter.length(16)), RepositoryEntity::type, SqlColumnBuilder::notNull);
	public static final SqlColumn<RepositoryEntity, String> REPOSITORY_DESCRIPTION = REPOSITORIES.column("description", SqlTypes.TEXT, RepositoryEntity::description);
	public static final SqlColumn<RepositoryEntity, Boolean> REPOSITORY_PUBLIC_READ = REPOSITORIES.column("public_read", SqlTypes.BOOLEAN, RepositoryEntity::publicRead, SqlColumnBuilder::notNull);
	public static final SqlColumn<RepositoryEntity, Boolean> REPOSITORY_ALLOW_REDEPLOY = REPOSITORIES.column("allow_redeploy", SqlTypes.BOOLEAN, RepositoryEntity::allowRedeploy, SqlColumnBuilder::notNull);
	public static final SqlColumn<RepositoryEntity, Instant> REPOSITORY_CREATED_AT = REPOSITORIES.column("created_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), RepositoryEntity::createdAt, SqlColumnBuilder::notNull);
	//endregion
	
	//region Permissions
	public static final SqlTable<PermissionEntity> PERMISSIONS = SqlTable.create(PermissionEntity.class, "permissions");
	public static final SqlColumn<PermissionEntity, String> PERMISSION_REPOSITORY = PERMISSIONS.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), PermissionEntity::repository, col -> col.primaryKey().notNull());
	public static final SqlColumn<PermissionEntity, String> PERMISSION_USERNAME = PERMISSIONS.column("username", SqlTypes.STRING.configure(SqlParameter.length(128)), PermissionEntity::username, col -> col.primaryKey().notNull());
	public static final SqlColumn<PermissionEntity, String> PERMISSION_LEVEL = PERMISSIONS.column("level", SqlTypes.STRING.configure(SqlParameter.length(16)), PermissionEntity::level, SqlColumnBuilder::notNull);
	public static final SqlCompositePrimaryKey<PermissionEntity> PERMISSION_PK = PERMISSIONS.compositePrimaryKey(PERMISSION_REPOSITORY, PERMISSION_USERNAME);
	//endregion
	
	//region Files
	public static final SqlTable<FileEntity> FILES = SqlTable.create(FileEntity.class, "files");
	public static final SqlColumn<FileEntity, String> FILE_REPOSITORY = FILES.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), FileEntity::repository, col -> col.primaryKey().notNull());
	public static final SqlColumn<FileEntity, String> FILE_PATH = FILES.column("path", SqlTypes.STRING.configure(SqlParameter.length(1024)), FileEntity::path, col -> col.primaryKey().notNull());
	public static final SqlColumn<FileEntity, String> FILE_PACKAGE = FILES.column("package_name", SqlTypes.STRING.configure(SqlParameter.length(256)), FileEntity::packageName);
	public static final SqlColumn<FileEntity, String> FILE_VERSION = FILES.column("version", SqlTypes.STRING.configure(SqlParameter.length(128)), FileEntity::version);
	public static final SqlColumn<FileEntity, Long> FILE_SIZE = FILES.column("size", SqlTypes.LONG, FileEntity::size, SqlColumnBuilder::notNull);
	public static final SqlColumn<FileEntity, String> FILE_SHA256 = FILES.column("sha256", SqlTypes.STRING.configure(SqlParameter.length(64)), FileEntity::sha256, SqlColumnBuilder::notNull);
	public static final SqlColumn<FileEntity, String> FILE_SHA1 = FILES.column("sha1", SqlTypes.STRING.configure(SqlParameter.length(40)), FileEntity::sha1, SqlColumnBuilder::notNull);
	public static final SqlColumn<FileEntity, String> FILE_MD5 = FILES.column("md5", SqlTypes.STRING.configure(SqlParameter.length(32)), FileEntity::md5, SqlColumnBuilder::notNull);
	public static final SqlColumn<FileEntity, String> FILE_SHA512 = FILES.column("sha512", SqlTypes.STRING.configure(SqlParameter.length(128)), FileEntity::sha512, SqlColumnBuilder::notNull);
	public static final SqlColumn<FileEntity, String> FILE_CONTENT_TYPE = FILES.column("content_type", SqlTypes.STRING.configure(SqlParameter.length(128)), FileEntity::contentType, SqlColumnBuilder::notNull);
	public static final SqlColumn<FileEntity, String> FILE_METADATA = FILES.column("metadata", SqlTypes.TEXT, FileEntity::metadata);
	public static final SqlColumn<FileEntity, Instant> FILE_CREATED_AT = FILES.column("created_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), FileEntity::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<FileEntity, String> FILE_CREATED_BY = FILES.column("created_by", SqlTypes.STRING.configure(SqlParameter.length(128)), FileEntity::createdBy);
	public static final SqlCompositePrimaryKey<FileEntity> FILE_PK = FILES.compositePrimaryKey(FILE_REPOSITORY, FILE_PATH);
	//endregion
	
	//region Package versions
	public static final SqlTable<PackageVersionEntity> PACKAGE_VERSIONS = SqlTable.create(PackageVersionEntity.class, "package_versions");
	public static final SqlColumn<PackageVersionEntity, String> VERSION_REPOSITORY = PACKAGE_VERSIONS.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), PackageVersionEntity::repository, col -> col.primaryKey().notNull());
	public static final SqlColumn<PackageVersionEntity, String> VERSION_PACKAGE = PACKAGE_VERSIONS.column("package_name", SqlTypes.STRING.configure(SqlParameter.length(256)), PackageVersionEntity::packageName, col -> col.primaryKey().notNull());
	public static final SqlColumn<PackageVersionEntity, String> VERSION_VERSION = PACKAGE_VERSIONS.column("version", SqlTypes.STRING.configure(SqlParameter.length(128)), PackageVersionEntity::version, col -> col.primaryKey().notNull());
	public static final SqlColumn<PackageVersionEntity, String> VERSION_METADATA = PACKAGE_VERSIONS.column("metadata", SqlTypes.TEXT, PackageVersionEntity::metadata, SqlColumnBuilder::notNull);
	public static final SqlColumn<PackageVersionEntity, Boolean> VERSION_YANKED = PACKAGE_VERSIONS.column("yanked", SqlTypes.BOOLEAN, PackageVersionEntity::yanked, SqlColumnBuilder::notNull);
	public static final SqlColumn<PackageVersionEntity, Instant> VERSION_CREATED_AT = PACKAGE_VERSIONS.column("created_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), PackageVersionEntity::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<PackageVersionEntity, String> VERSION_CREATED_BY = PACKAGE_VERSIONS.column("created_by", SqlTypes.STRING.configure(SqlParameter.length(128)), PackageVersionEntity::createdBy);
	public static final SqlCompositePrimaryKey<PackageVersionEntity> VERSION_PK = PACKAGE_VERSIONS.compositePrimaryKey(VERSION_REPOSITORY, VERSION_PACKAGE, VERSION_VERSION);
	//endregion
	
	//region Dist tags
	public static final SqlTable<DistTagEntity> DIST_TAGS = SqlTable.create(DistTagEntity.class, "dist_tags");
	public static final SqlColumn<DistTagEntity, String> TAG_REPOSITORY = DIST_TAGS.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), DistTagEntity::repository, col -> col.primaryKey().notNull());
	public static final SqlColumn<DistTagEntity, String> TAG_PACKAGE = DIST_TAGS.column("package_name", SqlTypes.STRING.configure(SqlParameter.length(256)), DistTagEntity::packageName, col -> col.primaryKey().notNull());
	public static final SqlColumn<DistTagEntity, String> TAG_NAME = DIST_TAGS.column("tag", SqlTypes.STRING.configure(SqlParameter.length(128)), DistTagEntity::tag, col -> col.primaryKey().notNull());
	public static final SqlColumn<DistTagEntity, String> TAG_VERSION = DIST_TAGS.column("version", SqlTypes.STRING.configure(SqlParameter.length(128)), DistTagEntity::version, SqlColumnBuilder::notNull);
	public static final SqlCompositePrimaryKey<DistTagEntity> TAG_PK = DIST_TAGS.compositePrimaryKey(TAG_REPOSITORY, TAG_PACKAGE, TAG_NAME);
	//endregion
	
	public static final List<SqlTable<?>> ALL = List.of(USERS, TOKENS, REPOSITORIES, PERMISSIONS, FILES, PACKAGE_VERSIONS, DIST_TAGS);
	
	private Tables() {}
}
