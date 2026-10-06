package net.luis.artifactory.config;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

public record ServerConfig(
	int port,
	@Nullable String baseUrl,
	@NonNull Path storagePath,
	long maxUploadSize,
	@NonNull String adminUsername,
	@Nullable String adminPassword
) {
	
	public ServerConfig {
		Objects.requireNonNull(storagePath, "Storage path must not be null");
		Objects.requireNonNull(adminUsername, "Admin username must not be null");
		
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
			Path.of(env.string(EnvKeys.STORAGE_PATH, "data")).toAbsolutePath(),
			env.integer(EnvKeys.MAX_UPLOAD_SIZE, 1024) * 1024L * 1024L,
			env.string(EnvKeys.ADMIN_USERNAME, "admin"),
			env.optional(EnvKeys.ADMIN_PASSWORD)
		);
	}
}
