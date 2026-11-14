package net.luis.artifactory.repository;

import net.luis.artifactory.database.entity.AuditEntity;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static net.luis.artifactory.database.Tables.*;

/**
 * Records and queries the audit log: administrative actions (repository, user, permission, token, webhook and package
 * changes), failed logins and lockouts, and artifacts published or deleted through the package protocols.
 * Recording never fails the triggering request: a failed audit insert is logged and swallowed.<br>
 */
public class AuditService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);
	public static final int DEFAULT_LIMIT = 100;
	public static final int MAX_LIMIT = 500;
	
	//region Action identifiers
	public static final String REPOSITORY_CREATE = "repository.create";
	public static final String REPOSITORY_UPDATE = "repository.update";
	public static final String REPOSITORY_DELETE = "repository.delete";
	public static final String PACKAGE_DELETE = "package.delete";
	public static final String USER_CREATE = "user.create";
	public static final String USER_UPDATE = "user.update";
	public static final String USER_DELETE = "user.delete";
	public static final String PERMISSION_SET = "permission.set";
	public static final String PERMISSION_DELETE = "permission.delete";
	public static final String TOKEN_CREATE = "token.create";
	public static final String TOKEN_DELETE = "token.delete";
	public static final String WEBHOOK_CREATE = "webhook.create";
	public static final String WEBHOOK_UPDATE = "webhook.update";
	public static final String WEBHOOK_DELETE = "webhook.delete";
	public static final String SESSION_CREATE = "session.create";
	public static final String LOGIN_FAILED = "login.failed";
	public static final String LOGIN_LOCKED = "login.locked";
	public static final String ARTIFACT_PUBLISH = "artifact.publish";
	public static final String ARTIFACT_DELETE = "artifact.delete";
	//endregion
	
	/**
	 * Keys of the request diagnostic context holding the authenticated user and the client address.<br>
	 */
	public static final String USER_KEY = "user";
	public static final String IP_KEY = "source_ip";
	private final SqlDatabase database;
	
	public AuditService(@NonNull SqlDatabase database) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
	}
	
	@Nullable
	private static String truncate(@Nullable String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() > max ? value.substring(0, max) : value;
	}
	
	/**
	 * Records an audit entry. Any failure is logged and swallowed so auditing never breaks the audited action.<br>
	 */
	public void record(@Nullable String actor, @NonNull String action, @Nullable String repository, @Nullable String target, @Nullable String ip, @Nullable String detail) {
		// Without an explicit value the user and address of the current request are used, they are set by the request handling
		actor = actor != null ? actor : MDC.get(USER_KEY);
		ip = ip != null ? ip : MDC.get(IP_KEY);
		try {
			AuditEntity entry = new AuditEntity(UUID.randomUUID().toString(), Instant.now(), actor, action, repository, truncate(target, 1024), ip, detail);
			this.database.from(AUDIT_LOG).insert(entry).execute();
		} catch (Exception e) {
			LOGGER.warn("Failed to record audit event {} (actor={}, repository={}, target={})", action, actor, repository, target, e);
		}
	}
	
	@NonNull
	private static SqlCondition condition(@Nullable String repository, @Nullable String actor, @Nullable String action, @Nullable Instant from, @Nullable Instant to) throws SqlException {
		List<SqlCondition> conditions = new ArrayList<>();
		if (repository != null && !repository.isBlank()) {
			conditions.add(Sql.equalTo(AUDIT_REPOSITORY, repository));
		}
		if (actor != null && !actor.isBlank()) {
			conditions.add(Sql.equalTo(AUDIT_ACTOR, actor));
		}
		if (action != null && !action.isBlank()) {
			conditions.add(Sql.equalTo(AUDIT_ACTION, action));
		}
		// from is inclusive and to is exclusive
		if (from != null) {
			conditions.add(Sql.before(AUDIT_TIMESTAMP, from).not());
		}
		if (to != null) {
			conditions.add(Sql.before(AUDIT_TIMESTAMP, to));
		}
		return conditions.isEmpty() ? Sql.isNull(AUDIT_ID).not() : SqlCondition.allOf(conditions);
	}
	
	/**
	 * Lists audit entries newest first, optionally filtered by repository, actor, action and time range, with offset based paging.<br>
	 * Filtering, ordering and paging happen in the database.<br>
	 */
	@NonNull
	public List<AuditEntity> list(@Nullable String repository, @Nullable String actor, @Nullable String action, @Nullable Instant from, @Nullable Instant to, int limit, long offset) throws SqlException {
		var query = this.database.from(AUDIT_LOG).select()
			.where(condition(repository, actor, action, from, to))
			.orderBy(AUDIT_TIMESTAMP.descending(), AUDIT_ID.ascending())
			.limit(Math.clamp(limit, 1, MAX_LIMIT));
		if (offset > 0) {
			query = query.offset(offset);
		}
		return query.fetch();
	}
	
	/**
	 * Counts the audit entries matching the filters of {@link #list}.<br>
	 */
	public long count(@Nullable String repository, @Nullable String actor, @Nullable String action, @Nullable Instant from, @Nullable Instant to) throws SqlException {
		return this.database.from(AUDIT_LOG).select().where(condition(repository, actor, action, from, to)).count();
	}
	
	/**
	 * Deletes the entries older than the retention period.<br>
	 *
	 * @param retentionDays The number of days entries are kept, 0 keeps them forever
	 * @return The number of deleted entries
	 */
	public int prune(int retentionDays) throws SqlException {
		if (retentionDays <= 0) {
			return 0;
		}
		return this.database.from(AUDIT_LOG).delete().where(Sql.before(AUDIT_TIMESTAMP, Instant.now().minus(Duration.ofDays(retentionDays)))).execute();
	}
}
