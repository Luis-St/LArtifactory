package net.luis.artifactory.dto.response;

import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * The direct content of a folder of a repository.<br>
 *
 * @param prefix The folder with a trailing slash, empty for the root
 * @param folders The names of the subfolders without a trailing slash, sorted
 * @param files The files directly in the folder, sorted by path
 */
public record TreeResponse(
	@NonNull String prefix,
	@NonNull List<String> folders,
	@NonNull List<FileResponse> files
) {}
