package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.AuditEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record AuditResponse(
	@NonNull String id,
	@NonNull Instant timestamp,
	@Nullable String actor,
	@NonNull String action,
	@Nullable String repository,
	@Nullable String target,
	@Nullable String ip,
	@Nullable String detail
) {

	public static @NonNull AuditResponse of(@NonNull AuditEntity entity) {
		return new AuditResponse(entity.id(), entity.timestamp(), entity.actor(), entity.action(), entity.repository(), entity.target(), entity.ip(), entity.detail());
	}
}
