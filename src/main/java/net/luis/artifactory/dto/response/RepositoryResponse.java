package net.luis.artifactory.dto.response;

import net.luis.artifactory.database.entity.RemoteConfigEntity;
import net.luis.artifactory.database.entity.RepositoryEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public record RepositoryResponse(
	@NonNull String name,
	@NonNull String type,
	@Nullable String description,
	boolean publicRead,
	boolean allowRedeploy,
	@NonNull Instant createdAt,
	@NonNull String url,
	@NonNull String kind,
	@Nullable RemoteInfo remote,
	@NonNull List<String> members
) {

	/**
	 * Upstream info of a remote repository, the password is never exposed.<br>
	 */
	public record RemoteInfo(
		@NonNull String url,
		boolean cacheEnabled,
		@Nullable String username
	) {
		public static @NonNull RemoteInfo of(@NonNull RemoteConfigEntity entity) {
			return new RemoteInfo(entity.url(), entity.cacheEnabled(), entity.username());
		}
	}

	public static @NonNull RepositoryResponse of(@NonNull RepositoryEntity entity, @NonNull String baseUrl) {
		return of(entity, baseUrl, "LOCAL", null, List.of());
	}

	public static @NonNull RepositoryResponse of(@NonNull RepositoryEntity entity, @NonNull String baseUrl, @NonNull String kind, @Nullable RemoteInfo remote, @NonNull List<String> members) {
		return new RepositoryResponse(entity.name(), entity.type(), entity.description(), entity.publicRead(), entity.allowRedeploy(), entity.createdAt(), baseUrl + "/" + entity.type() + "/" + entity.name(), kind, remote, members);
	}
}
