package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;

public record PermissionEntity(
	@NonNull String repository,
	@NonNull String username,
	@NonNull String level
) {}
