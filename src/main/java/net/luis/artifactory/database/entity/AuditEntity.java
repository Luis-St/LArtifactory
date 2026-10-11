package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * A recorded administrative action.<br>
 *
 * @param id Unique id of the entry
 * @param timestamp When the action happened
 * @param actor The user that performed the action, null for anonymous/system actions
 * @param action The action identifier, e.g. {@code repository.create}
 * @param repository The repository the action relates to, null if not repository scoped
 * @param target The subject of the action (package, user, token id, path, ...), null if none
 * @param ip The client ip the action was performed from, null if unknown
 * @param detail Optional human readable detail
 */
public record AuditEntity(
	@NonNull String id,
	@NonNull Instant timestamp,
	@Nullable String actor,
	@NonNull String action,
	@Nullable String repository,
	@Nullable String target,
	@Nullable String ip,
	@Nullable String detail
) {}
