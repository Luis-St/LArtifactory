package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/**
 * One package with its versions, tags and all files of all versions.<br>
 */
public record PackageDetailResponse(
	@NonNull String name,
	@NonNull List<PackageResponse.VersionResponse> versions,
	@NonNull Map<String, String> tags,
	@NonNull List<FileResponse> files
) {}
