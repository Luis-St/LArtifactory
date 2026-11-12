package net.luis.artifactory.database.entity;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Per token settings and usage, stored separately from {@link TokenEntity} and keyed by the token id.<br>
 *
 * @param tokenId The id of the token this metadata belongs to
 * @param allowedIps Newline/comma separated list of allowed client IPs or CIDR ranges, null/blank allows any address
 * @param rateLimit Maximum number of requests per minute the token may make, {@code 0} means unlimited
 * @param lastUsedAt When the token was last used to authenticate a request, null if never used
 * @param lastUsedIp The client IP the token was last used from, null if never used
 */
public record TokenMetaEntity(
	@NonNull String tokenId,
	@Nullable String allowedIps,
	long rateLimit,
	@Nullable Instant lastUsedAt,
	@Nullable String lastUsedIp
) {}
