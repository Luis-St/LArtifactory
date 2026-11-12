package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Configuration of a remote (proxy) repository: artifacts not found locally are fetched from the upstream url and,
 * when caching is enabled, stored locally on first access.<br>
 *
 * @param repository The name of the repository this configuration belongs to
 * @param url The upstream base url (without a trailing slash)
 * @param cacheEnabled Whether fetched artifacts are stored locally
 * @param username Optional username for upstream basic authentication
 * @param password Optional password for upstream basic authentication
 */
public record RemoteConfigEntity(
	@NonNull String repository,
	@NonNull String url,
	boolean cacheEnabled,
	@Nullable String username,
	@Nullable String password
) {}
