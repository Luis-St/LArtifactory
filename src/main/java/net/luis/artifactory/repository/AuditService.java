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

import java.time.Instant;
import java.util.*;

import static net.luis.artifactory.database.Tables.*;

/**
 * Records and queries the audit log of administrative actions (repository, user, permission, token and package
 * changes). Recording never fails the triggering request: a failed audit insert is logged and swallowed.<br>
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
	//endregion

	private final SqlDatabase database;

	public AuditService(@NonNull SqlDatabase database) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
	}

	/**
	 * Records an audit entry. Any failure is logged and swallowed so auditing never breaks the audited action.<br>
	 */
	public void record(@Nullable String actor, @NonNull String action, @Nullable String repository, @Nullable String target, @Nullable String ip, @Nullable String detail) {
		try {
			AuditEntity entry = new AuditEntity(UUID.randomUUID().toString(), Instant.now(), actor, action, repository, truncate(target, 1024), ip, detail);
			this.database.from(AUDIT_LOG).insert(entry).execute();
		} catch (Exception e) {
			LOGGER.warn("Failed to record audit event {} (actor={}, repository={}, target={})", action, actor, repository, target, e);
		}
	}

	/**
	 * Lists audit entries newest first, optionally filtered by repository, actor or action, with offset based paging.<br>
	 */
	public @NonNull List<AuditEntity> list(@Nullable String repository, @Nullable String actor, @Nullable String action, int limit, int offset) throws SqlException {
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
		var query = this.database.from(AUDIT_LOG).select();
		if (!conditions.isEmpty()) {
			query = query.where(SqlCondition.allOf(conditions));
		}
		// The SQL builder in use only orders ascending, so fetch ordered then reverse to newest first and page in memory.
		List<AuditEntity> ordered = new ArrayList<>(query.orderBy(AUDIT_TIMESTAMP.ascending()).fetch());
		Collections.reverse(ordered);
		int boundedLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
		return ordered.stream().skip(Math.max(0, offset)).limit(boundedLimit).toList();
	}

	/**
	 * Deletes all audit entries of a repository (used when the repository itself is deleted is intentionally avoided so
	 * the deletion record survives; this is exposed for completeness and is currently unused).<br>
	 */
	public int deleteForRepository(@NonNull String repository) throws SqlException {
		return this.database.from(AUDIT_LOG).delete().where(Sql.equalTo(AUDIT_REPOSITORY, repository)).execute();
	}

	private static @Nullable String truncate(@Nullable String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() > max ? value.substring(0, max) : value;
	}
}
