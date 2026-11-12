package net.luis.artifactory.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.*;

public record ServerConfig(
	int port,
	@Nullable String baseUrl,
	@NonNull Map<String, String> hostRepositories,
	@NonNull Path storagePath,
	long maxUploadSize,
	long maxBufferedUploadSize,
	@NonNull String adminUsername,
	@Nullable String adminPassword,
	boolean trustProxy,
	boolean apiDocsEnabled,
	int loginMaxFailures,
	int loginLockoutMinutes,
	int sessionHours,
	int sessionRememberDays,
	int npmTokenDays,
	int maintenanceIntervalMinutes
) {
	
	public ServerConfig {
		Objects.requireNonNull(storagePath, "Storage path must not be null");
		Objects.requireNonNull(adminUsername, "Admin username must not be null");
		hostRepositories = Map.copyOf(Objects.requireNonNull(hostRepositories, "Host repositories must not be null"));
		
		if (port < 1 || port > 65535) {
			throw new ConfigException(EnvKeys.PORT + " must be between 1 and 65535, got: " + port);
		}
		if (maxUploadSize < 1) {
			throw new ConfigException(EnvKeys.MAX_UPLOAD_SIZE + " must be at least 1, got: " + maxUploadSize);
		}
		if (maxBufferedUploadSize < 1) {
			throw new ConfigException(EnvKeys.MAX_BUFFERED_UPLOAD_SIZE + " must be at least 1, got: " + maxBufferedUploadSize);
		}
		requireAtLeast(EnvKeys.LOGIN_MAX_FAILURES, loginMaxFailures, 1);
		requireAtLeast(EnvKeys.LOGIN_LOCKOUT_MINUTES, loginLockoutMinutes, 1);
		requireAtLeast(EnvKeys.SESSION_HOURS, sessionHours, 1);
		requireAtLeast(EnvKeys.SESSION_REMEMBER_DAYS, sessionRememberDays, 1);
		requireAtLeast(EnvKeys.NPM_TOKEN_DAYS, npmTokenDays, 0);
		requireAtLeast(EnvKeys.MAINTENANCE_INTERVAL, maintenanceIntervalMinutes, 1);
		if (baseUrl != null) {
			baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		}
	}
	
	@NonNull
	public static ServerConfig from(@NonNull Env env) {
		return new ServerConfig(
			env.integer(EnvKeys.PORT, 8080),
			env.optional(EnvKeys.BASE_URL),
			parseHostRepositories(env.optional(EnvKeys.HOST_REPOSITORIES)),
			Path.of(env.string(EnvKeys.STORAGE_PATH, "data")).toAbsolutePath(),
			env.integer(EnvKeys.MAX_UPLOAD_SIZE, 1024) * 1024L * 1024L,
			env.integer(EnvKeys.MAX_BUFFERED_UPLOAD_SIZE, 256) * 1024L * 1024L,
			env.string(EnvKeys.ADMIN_USERNAME, "admin"),
			env.optional(EnvKeys.ADMIN_PASSWORD),
			env.bool(EnvKeys.TRUST_PROXY, false),
			env.bool(EnvKeys.ENABLE_API_DOCS, false),
			env.integer(EnvKeys.LOGIN_MAX_FAILURES, 10),
			env.integer(EnvKeys.LOGIN_LOCKOUT_MINUTES, 15),
			env.integer(EnvKeys.SESSION_HOURS, 12),
			env.integer(EnvKeys.SESSION_REMEMBER_DAYS, 30),
			env.integer(EnvKeys.NPM_TOKEN_DAYS, 90),
			env.integer(EnvKeys.MAINTENANCE_INTERVAL, 60)
		);
	}
	
	private static void requireAtLeast(@NonNull String key, int value, int min) {
		if (value < min) {
			throw new ConfigException(key + " must be at least " + min + ", got: " + value);
		}
	}
	
	/**
	 * Parses a comma separated list of {@code host=repository} pairs.<br>
	 * Requests to a mapped host are served by the repository at the root path, e.g. {@code npm.example.com=npm-local}.<br>
	 */
	@NonNull
	private static Map<String, String> parseHostRepositories(@Nullable String value) {
		Map<String, String> mapping = new HashMap<>();
		if (value == null) {
			return mapping;
		}
		for (String entry : value.split(",")) {
			if (entry.isBlank()) {
				continue;
			}
			int equals = entry.indexOf('=');
			if (equals <= 0 || equals == entry.length() - 1) {
				throw new ConfigException(EnvKeys.HOST_REPOSITORIES + " entries must have the format host=repository, got: " + entry);
			}
			mapping.put(entry.substring(0, equals).strip().toLowerCase(Locale.ROOT), entry.substring(equals + 1).strip());
		}
		return mapping;
	}
}
