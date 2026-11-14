package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A package of the cross repository package list with its versions, tags and the size of its files.<br>
 */
public record PackageSummaryResponse(
	@NonNull String repository,
	@NonNull String type,
	@NonNull String name,
	@NonNull List<PackageResponse.VersionResponse> versions,
	@NonNull Map<String, String> tags,
	long files,
	long size,
	@Nullable Instant updatedAt
) {}
