package net.luis.artifactory.auth;

import net.luis.artifactory.database.entity.TokenMetaEntity;
import net.luis.artifactory.http.HttpError;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces the per token settings (ip allowlist and rate limit) and records the last usage of a token.<br>
 * <p>
 *     Token settings are cached in memory for a short time so the check does not hit the database on every request,
 *     rate limiting uses an in memory fixed window counter and the {@code last used} timestamp is only persisted
 *     periodically to avoid a database write per request.<br>
 * </p>
 */
public class TokenUsageService {

	private static final long META_CACHE_TTL_MILLIS = 15_000;
	private static final long USAGE_PERSIST_INTERVAL_MILLIS = 60_000;
	private static final long WINDOW_MILLIS = 60_000;

	private final UserService userService;
	// token id -> cached settings (empty = no settings row, i.e. an unrestricted legacy token)
	private final ConcurrentHashMap<String, CachedMeta> metaCache = new ConcurrentHashMap<>();
	// token id -> [windowStartMillis, count] fixed window rate limit counter
	private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();
	// token id -> last time the usage was persisted
	private final ConcurrentHashMap<String, Long> lastPersist = new ConcurrentHashMap<>();

	public TokenUsageService(@NonNull UserService userService) {
		this.userService = Objects.requireNonNull(userService, "User service must not be null");
	}

	/**
	 * Clears the cached settings of a token (call after the token or its settings change).<br>
	 */
	public void invalidate(@NonNull String tokenId) {
		this.metaCache.remove(tokenId);
		this.windows.remove(tokenId);
		this.lastPersist.remove(tokenId);
	}

	/**
	 * Enforces the ip allowlist and the rate limit of a token and records that it was used.<br>
	 *
	 * @throws HttpError 403 if the client ip is not in the token allowlist, 429 if the rate limit is exceeded
	 */
	public void enforceAndRecord(@NonNull String tokenId, @Nullable String clientIp) throws SqlException {
		TokenMetaEntity meta = this.meta(tokenId);
		if (meta == null) {
			return; // Legacy token without a settings row, unrestricted and not tracked
		}
		if (!ipAllowed(clientIp, meta.allowedIps())) {
			throw HttpError.forbidden("Token is not permitted from this address");
		}
		if (meta.rateLimit() > 0 && !this.allowRequest(tokenId, meta.rateLimit())) {
			throw HttpError.tooManyRequests(secondsUntilNextWindow());
		}
		this.recordUsage(tokenId, clientIp);
	}

	private @Nullable TokenMetaEntity meta(@NonNull String tokenId) throws SqlException {
		CachedMeta cached = this.metaCache.get(tokenId);
		if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
			return cached.meta().orElse(null);
		}
		TokenMetaEntity meta = this.userService.getTokenMeta(tokenId);
		this.metaCache.put(tokenId, new CachedMeta(Optional.ofNullable(meta), System.currentTimeMillis() + META_CACHE_TTL_MILLIS));
		return meta;
	}

	private boolean allowRequest(@NonNull String tokenId, long limit) {
		long now = System.currentTimeMillis();
		long windowStart = now - (now % WINDOW_MILLIS);
		long[] window = this.windows.compute(tokenId, (_, current) -> {
			if (current == null || current[0] != windowStart) {
				return new long[] { windowStart, 1 };
			}
			current[1]++;
			return current;
		});
		return window[1] <= limit;
	}

	private static long secondsUntilNextWindow() {
		return (WINDOW_MILLIS - System.currentTimeMillis() % WINDOW_MILLIS) / 1000 + 1;
	}

	private void recordUsage(@NonNull String tokenId, @Nullable String clientIp) throws SqlException {
		long now = System.currentTimeMillis();
		Long previous = this.lastPersist.get(tokenId);
		if (previous != null && now - previous < USAGE_PERSIST_INTERVAL_MILLIS) {
			return;
		}
		this.lastPersist.put(tokenId, now);
		this.userService.recordTokenUsage(tokenId, Instant.now(), clientIp);
		this.metaCache.remove(tokenId); // Next read reflects the recorded usage
	}

	//region IP allowlist matching
	/**
	 * Whether the client ip is allowed by the (comma/whitespace separated) allowlist of IPs and CIDR ranges.<br>
	 * A null or blank allowlist allows any address, an unparseable client ip is denied.<br>
	 */
	public static boolean ipAllowed(@Nullable String clientIp, @Nullable String allowList) {
		if (allowList == null || allowList.isBlank()) {
			return true;
		}
		if (clientIp == null || clientIp.isBlank()) {
			return false;
		}
		InetAddress client = parse(clientIp);
		if (client == null) {
			return false;
		}
		for (String raw : allowList.split("[,\\s]+")) {
			String entry = raw.strip();
			if (entry.isEmpty()) {
				continue;
			}
			if (entry.contains("/")) {
				if (cidrMatch(client, entry)) {
					return true;
				}
			} else {
				InetAddress candidate = parse(entry);
				if (candidate != null && Arrays.equals(candidate.getAddress(), client.getAddress())) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean cidrMatch(@NonNull InetAddress client, @NonNull String cidr) {
		int slash = cidr.indexOf('/');
		InetAddress base = parse(cidr.substring(0, slash));
		if (base == null) {
			return false;
		}
		int prefix;
		try {
			prefix = Integer.parseInt(cidr.substring(slash + 1).strip());
		} catch (NumberFormatException e) {
			return false;
		}
		byte[] clientBytes = client.getAddress();
		byte[] baseBytes = base.getAddress();
		if (clientBytes.length != baseBytes.length || prefix < 0 || prefix > clientBytes.length * 8) {
			return false;
		}
		int fullBytes = prefix / 8;
		for (int i = 0; i < fullBytes; i++) {
			if (clientBytes[i] != baseBytes[i]) {
				return false;
			}
		}
		int remainingBits = prefix % 8;
		if (remainingBits > 0) {
			int mask = (0xFF << (8 - remainingBits)) & 0xFF;
			return (clientBytes[fullBytes] & mask) == (baseBytes[fullBytes] & mask);
		}
		return true;
	}

	/**
	 * Parses a literal IPv4/IPv6 address without a DNS lookup, returns null if the value is not a literal address.<br>
	 */
	private static @Nullable InetAddress parse(@NonNull String value) {
		String host = value.strip();
		if (host.startsWith("[") && host.endsWith("]")) {
			host = host.substring(1, host.length() - 1);
		}
		// Only accept literal addresses: a value without ':' must be dotted IPv4 to avoid a hostname DNS lookup
		if (!host.contains(":") && !host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
			return null;
		}
		try {
			return InetAddress.getByName(host);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Validates an ip allowlist (used when creating a token), returns false if any entry is not a valid IP or CIDR.<br>
	 */
	public static boolean validAllowList(@Nullable String allowList) {
		if (allowList == null || allowList.isBlank()) {
			return true;
		}
		for (String raw : allowList.split("[,\\s]+")) {
			String entry = raw.strip();
			if (entry.isEmpty()) {
				continue;
			}
			String address = entry;
			if (entry.contains("/")) {
				int slash = entry.indexOf('/');
				try {
					int prefix = Integer.parseInt(entry.substring(slash + 1).strip());
					if (prefix < 0 || prefix > 128) {
						return false;
					}
				} catch (NumberFormatException e) {
					return false;
				}
				address = entry.substring(0, slash);
			}
			if (parse(address) == null) {
				return false;
			}
		}
		return true;
	}
	//endregion

	private record CachedMeta(@NonNull Optional<TokenMetaEntity> meta, long expiresAt) {}
}
