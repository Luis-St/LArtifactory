package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;

/**
 * The class of a repository (LOCAL, REMOTE or VIRTUAL), stored explicitly so it does not depend on the current
 * remote configuration or members of the repository.<br>
 *
 * @param repository The name of the repository
 * @param kind The name of the {@link net.luis.artifactory.repository.RepositoryClass}
 */
public record RepositoryKindEntity(
	@NonNull String repository,
	@NonNull String kind
) {}
