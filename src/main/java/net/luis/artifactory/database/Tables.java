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

	//region Token metadata (settings and usage, keyed by token id)
	public static final SqlTable<TokenMetaEntity> TOKEN_META = SqlTable.create(TokenMetaEntity.class, "token_meta");
	public static final SqlColumn<TokenMetaEntity, String> TOKEN_META_ID = TOKEN_META.column("token_id", SqlTypes.STRING.configure(SqlParameter.length(36)), TokenMetaEntity::tokenId, col -> col.primaryKey().notNull());
	public static final SqlColumn<TokenMetaEntity, String> TOKEN_META_ALLOWED_IPS = TOKEN_META.column("allowed_ips", SqlTypes.TEXT, TokenMetaEntity::allowedIps);
	public static final SqlColumn<TokenMetaEntity, Long> TOKEN_META_RATE_LIMIT = TOKEN_META.column("rate_limit", SqlTypes.LONG, TokenMetaEntity::rateLimit, SqlColumnBuilder::notNull);
	public static final SqlColumn<TokenMetaEntity, Instant> TOKEN_META_LAST_USED_AT = TOKEN_META.column("last_used_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), TokenMetaEntity::lastUsedAt);
	public static final SqlColumn<TokenMetaEntity, String> TOKEN_META_LAST_USED_IP = TOKEN_META.column("last_used_ip", SqlTypes.STRING.configure(SqlParameter.length(64)), TokenMetaEntity::lastUsedIp);
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
	
	//region Remote (proxy) repositories
	public static final SqlTable<RemoteConfigEntity> REMOTE_REPOSITORIES = SqlTable.create(RemoteConfigEntity.class, "remote_repositories");
	public static final SqlColumn<RemoteConfigEntity, String> REMOTE_REPOSITORY = REMOTE_REPOSITORIES.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), RemoteConfigEntity::repository, col -> col.primaryKey().notNull());
	public static final SqlColumn<RemoteConfigEntity, String> REMOTE_URL = REMOTE_REPOSITORIES.column("url", SqlTypes.STRING.configure(SqlParameter.length(1024)), RemoteConfigEntity::url, SqlColumnBuilder::notNull);
	public static final SqlColumn<RemoteConfigEntity, Boolean> REMOTE_CACHE_ENABLED = REMOTE_REPOSITORIES.column("cache_enabled", SqlTypes.BOOLEAN, RemoteConfigEntity::cacheEnabled, SqlColumnBuilder::notNull);
	public static final SqlColumn<RemoteConfigEntity, String> REMOTE_USERNAME = REMOTE_REPOSITORIES.column("username", SqlTypes.STRING.configure(SqlParameter.length(256)), RemoteConfigEntity::username);
	public static final SqlColumn<RemoteConfigEntity, String> REMOTE_PASSWORD = REMOTE_REPOSITORIES.column("password", SqlTypes.STRING.configure(SqlParameter.length(512)), RemoteConfigEntity::password);
	//endregion

	//region Virtual (group) repositories
	public static final SqlTable<VirtualMemberEntity> VIRTUAL_MEMBERS = SqlTable.create(VirtualMemberEntity.class, "virtual_members");
	public static final SqlColumn<VirtualMemberEntity, String> VIRTUAL_REPOSITORY = VIRTUAL_MEMBERS.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), VirtualMemberEntity::repository, col -> col.primaryKey().notNull());
	public static final SqlColumn<VirtualMemberEntity, String> VIRTUAL_MEMBER = VIRTUAL_MEMBERS.column("member", SqlTypes.STRING.configure(SqlParameter.length(64)), VirtualMemberEntity::member, col -> col.primaryKey().notNull());
	public static final SqlColumn<VirtualMemberEntity, Long> VIRTUAL_POSITION = VIRTUAL_MEMBERS.column("position", SqlTypes.LONG, VirtualMemberEntity::position, SqlColumnBuilder::notNull);
	public static final SqlCompositePrimaryKey<VirtualMemberEntity> VIRTUAL_PK = VIRTUAL_MEMBERS.compositePrimaryKey(VIRTUAL_REPOSITORY, VIRTUAL_MEMBER);
	//endregion

	//region Webhooks
	public static final SqlTable<WebhookEntity> WEBHOOKS = SqlTable.create(WebhookEntity.class, "webhooks");
	public static final SqlColumn<WebhookEntity, String> WEBHOOK_ID = WEBHOOKS.column("id", SqlTypes.STRING.configure(SqlParameter.length(36)), WebhookEntity::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<WebhookEntity, String> WEBHOOK_REPOSITORY = WEBHOOKS.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), WebhookEntity::repository);
	public static final SqlColumn<WebhookEntity, String> WEBHOOK_URL = WEBHOOKS.column("url", SqlTypes.STRING.configure(SqlParameter.length(1024)), WebhookEntity::url, SqlColumnBuilder::notNull);
	public static final SqlColumn<WebhookEntity, String> WEBHOOK_SECRET = WEBHOOKS.column("secret", SqlTypes.STRING.configure(SqlParameter.length(256)), WebhookEntity::secret);
	public static final SqlColumn<WebhookEntity, String> WEBHOOK_EVENTS = WEBHOOKS.column("events", SqlTypes.TEXT, WebhookEntity::events);
	public static final SqlColumn<WebhookEntity, Boolean> WEBHOOK_ACTIVE = WEBHOOKS.column("active", SqlTypes.BOOLEAN, WebhookEntity::active, SqlColumnBuilder::notNull);
	public static final SqlColumn<WebhookEntity, Instant> WEBHOOK_CREATED_AT = WEBHOOKS.column("created_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), WebhookEntity::createdAt, SqlColumnBuilder::notNull);
	public static final SqlColumn<WebhookEntity, String> WEBHOOK_CREATED_BY = WEBHOOKS.column("created_by", SqlTypes.STRING.configure(SqlParameter.length(128)), WebhookEntity::createdBy);
	//endregion

	//region Webhook deliveries
	public static final SqlTable<WebhookDeliveryEntity> WEBHOOK_DELIVERIES = SqlTable.create(WebhookDeliveryEntity.class, "webhook_deliveries");
	public static final SqlColumn<WebhookDeliveryEntity, String> DELIVERY_ID = WEBHOOK_DELIVERIES.column("id", SqlTypes.STRING.configure(SqlParameter.length(36)), WebhookDeliveryEntity::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<WebhookDeliveryEntity, String> DELIVERY_WEBHOOK = WEBHOOK_DELIVERIES.column("webhook_id", SqlTypes.STRING.configure(SqlParameter.length(36)), WebhookDeliveryEntity::webhookId, SqlColumnBuilder::notNull);
	public static final SqlColumn<WebhookDeliveryEntity, String> DELIVERY_EVENT = WEBHOOK_DELIVERIES.column("event", SqlTypes.STRING.configure(SqlParameter.length(64)), WebhookDeliveryEntity::event, SqlColumnBuilder::notNull);
	public static final SqlColumn<WebhookDeliveryEntity, String> DELIVERY_REPOSITORY = WEBHOOK_DELIVERIES.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), WebhookDeliveryEntity::repository);
	public static final SqlColumn<WebhookDeliveryEntity, Boolean> DELIVERY_SUCCESS = WEBHOOK_DELIVERIES.column("success", SqlTypes.BOOLEAN, WebhookDeliveryEntity::success, SqlColumnBuilder::notNull);
	public static final SqlColumn<WebhookDeliveryEntity, Long> DELIVERY_STATUS = WEBHOOK_DELIVERIES.column("status_code", SqlTypes.LONG, WebhookDeliveryEntity::statusCode, SqlColumnBuilder::notNull);
	public static final SqlColumn<WebhookDeliveryEntity, String> DELIVERY_ERROR = WEBHOOK_DELIVERIES.column("error", SqlTypes.TEXT, WebhookDeliveryEntity::error);
	public static final SqlColumn<WebhookDeliveryEntity, Instant> DELIVERY_CREATED_AT = WEBHOOK_DELIVERIES.column("created_at", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), WebhookDeliveryEntity::createdAt, SqlColumnBuilder::notNull);
	//endregion

	//region Audit log
	public static final SqlTable<AuditEntity> AUDIT_LOG = SqlTable.create(AuditEntity.class, "audit_log");
	public static final SqlColumn<AuditEntity, String> AUDIT_ID = AUDIT_LOG.column("id", SqlTypes.STRING.configure(SqlParameter.length(36)), AuditEntity::id, col -> col.primaryKey().notNull());
	public static final SqlColumn<AuditEntity, Instant> AUDIT_TIMESTAMP = AUDIT_LOG.column("timestamp", SqlTypes.INSTANT.configure(SqlParameter.fractional(6)), AuditEntity::timestamp, SqlColumnBuilder::notNull);
	public static final SqlColumn<AuditEntity, String> AUDIT_ACTOR = AUDIT_LOG.column("actor", SqlTypes.STRING.configure(SqlParameter.length(128)), AuditEntity::actor);
	public static final SqlColumn<AuditEntity, String> AUDIT_ACTION = AUDIT_LOG.column("action", SqlTypes.STRING.configure(SqlParameter.length(64)), AuditEntity::action, SqlColumnBuilder::notNull);
	public static final SqlColumn<AuditEntity, String> AUDIT_REPOSITORY = AUDIT_LOG.column("repository", SqlTypes.STRING.configure(SqlParameter.length(64)), AuditEntity::repository);
	public static final SqlColumn<AuditEntity, String> AUDIT_TARGET = AUDIT_LOG.column("target", SqlTypes.STRING.configure(SqlParameter.length(1024)), AuditEntity::target);
	public static final SqlColumn<AuditEntity, String> AUDIT_IP = AUDIT_LOG.column("ip", SqlTypes.STRING.configure(SqlParameter.length(64)), AuditEntity::ip);
	public static final SqlColumn<AuditEntity, String> AUDIT_DETAIL = AUDIT_LOG.column("detail", SqlTypes.TEXT, AuditEntity::detail);
	//endregion

	public static final List<SqlTable<?>> ALL = List.of(USERS, TOKENS, TOKEN_META, REPOSITORIES, PERMISSIONS, FILES, PACKAGE_VERSIONS, DIST_TAGS, AUDIT_LOG, WEBHOOKS, WEBHOOK_DELIVERIES, REMOTE_REPOSITORIES, VIRTUAL_MEMBERS);
	
	private Tables() {}
}
