package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.RepositoryEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record RepositoryResponse(
	@NonNull String name,
	@NonNull String type,
	@Nullable String description,
	boolean publicRead,
	boolean allowRedeploy,
	@NonNull Instant createdAt,
	@NonNull String url
) {
	
	public static @NonNull RepositoryResponse of(@NonNull RepositoryEntity entity, @NonNull String baseUrl) {
		return new RepositoryResponse(entity.name(), entity.type(), entity.description(), entity.publicRead(), entity.allowRedeploy(), entity.createdAt(), baseUrl + "/" + entity.type() + "/" + entity.name());
	}
}
