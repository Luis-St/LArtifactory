package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.FileEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record FileResponse(
	@NonNull String path,
	@Nullable String packageName,
	@Nullable String version,
	long size,
	@NonNull String sha256,
	@NonNull String sha1,
	@NonNull String md5,
	@NonNull String contentType,
	@NonNull Instant createdAt,
	@Nullable String createdBy
) {
	
	public static @NonNull FileResponse of(@NonNull FileEntity entity) {
		return new FileResponse(entity.path(), entity.packageName(), entity.version(), entity.size(), entity.sha256(), entity.sha1(), entity.md5(), entity.contentType(), entity.createdAt(), entity.createdBy());
	}
}
