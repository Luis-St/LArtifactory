package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record TokenEntity(
	@NonNull String id,
	@NonNull String username,
	@NonNull String name,
	@NonNull String tokenHash,
	@NonNull String level,
	@NonNull Instant createdAt,
	@Nullable Instant expiresAt
) {}
