package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record PackageVersionEntity(
	@NonNull String repository,
	@NonNull String packageName,
	@NonNull String version,
	@NonNull String metadata,
	boolean yanked,
	@NonNull Instant createdAt,
	@Nullable String createdBy
) {}
