package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record RepositoryEntity(
	@NonNull String name,
	@NonNull String type,
	@Nullable String description,
	boolean publicRead,
	boolean allowRedeploy,
	@NonNull Instant createdAt
) {}
