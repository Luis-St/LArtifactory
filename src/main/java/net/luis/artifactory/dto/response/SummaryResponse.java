package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Counts and sizes of the repositories readable by the caller, the latest publications and the publications per hour.<br>
 *
 * @param repositories The readable repositories with their content counts
 * @param recent The newest package versions across the readable repositories, newest first
 * @param activity Package versions (files for generic repositories) published per hour and type, over the last days
 */
public record SummaryResponse(
	@NonNull List<RepositorySummary> repositories,
	@NonNull List<RecentVersion> recent,
	@NonNull List<ActivityBucket> activity
) {
	
	/**
	 * @param size The total size of the stored files, before deduplication
	 * @param lastUpload The time the newest file was stored, null if the repository is empty
	 */
	public record RepositorySummary(
		@NonNull String name,
		@NonNull String type,
		@NonNull String kind,
		@Nullable String description,
		boolean publicRead,
		long packages,
		long versions,
		long files,
		long size,
		@Nullable Instant lastUpload
	) {}
	
	/**
	 * @param tags The tags pointing at the version, the readable name of an OCI manifest
	 */
	public record RecentVersion(
		@NonNull String repository,
		@NonNull String type,
		@NonNull String packageName,
		@NonNull String version,
		@NonNull Instant createdAt,
		@Nullable String createdBy,
		@NonNull List<String> tags
	) {}
	
	/**
	 * @param hour The start of the hour (UTC)
	 * @param counts The number of publications per repository type
	 */
	public record ActivityBucket(
		@NonNull Instant hour,
		@NonNull Map<String, Long> counts
	) {}
}
