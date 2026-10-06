package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;

public record DistTagEntity(
	@NonNull String repository,
	@NonNull String packageName,
	@NonNull String tag,
	@NonNull String version
) {}
