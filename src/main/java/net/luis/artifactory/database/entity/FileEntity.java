package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record FileEntity(
	@NonNull String repository,
	@NonNull String path,
	@Nullable String packageName,
	@Nullable String version,
	long size,
	@NonNull String sha256,
	@NonNull String sha1,
	@NonNull String md5,
	@NonNull String sha512,
	@NonNull String contentType,
	@Nullable String metadata,
	@NonNull Instant createdAt,
	@Nullable String createdBy
) {}
