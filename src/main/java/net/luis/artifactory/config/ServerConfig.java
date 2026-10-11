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
	@NonNull String adminUsername,
	@Nullable String adminPassword,
	boolean trustProxy,
	boolean enableSwagger
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
		if (baseUrl != null) {
			baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		}
	}
	
	public static @NonNull ServerConfig from(@NonNull Env env) {
		return new ServerConfig(
			env.integer(EnvKeys.PORT, 8080),
			env.optional(EnvKeys.BASE_URL),
			parseHostRepositories(env.optional(EnvKeys.HOST_REPOSITORIES)),
			Path.of(env.string(EnvKeys.STORAGE_PATH, "data")).toAbsolutePath(),
			env.integer(EnvKeys.MAX_UPLOAD_SIZE, 1024) * 1024L * 1024L,
			env.string(EnvKeys.ADMIN_USERNAME, "admin"),
			env.optional(EnvKeys.ADMIN_PASSWORD),
			env.bool(EnvKeys.TRUST_PROXY, false),
			env.bool(EnvKeys.ENABLE_SWAGGER, true)
		);
	}
	
	/**
	 * Parses a comma separated list of {@code host=repository} pairs.<br>
	 * Requests to a mapped host are served by the repository at the root path, e.g. {@code npm.example.com=npm-local}.<br>
	 */
	private static @NonNull Map<String, String> parseHostRepositories(@Nullable String value) {
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
