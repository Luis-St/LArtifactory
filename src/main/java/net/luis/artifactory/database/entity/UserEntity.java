package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;

import java.time.Instant;

public record UserEntity(
	@NonNull String username,
	@NonNull String passwordHash,
	boolean admin,
	@NonNull Instant createdAt
) {}
