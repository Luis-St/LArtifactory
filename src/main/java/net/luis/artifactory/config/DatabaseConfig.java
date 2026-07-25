package net.luis.artifactory.config;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record DatabaseConfig(
	@NonNull String url,
	@NonNull String user,
	@NonNull String password,
	int poolSize
) {
	
	public DatabaseConfig {
		Objects.requireNonNull(url, "Database URL must not be null");
		Objects.requireNonNull(user, "Database user must not be null");
		Objects.requireNonNull(password, "Database password must not be null");
		
		if (poolSize < 1) {
			throw new ConfigException(EnvKeys.DB_POOL_SIZE + " must be at least 1, got: " + poolSize);
		}
	}
	
	public static @NonNull DatabaseConfig from(@NonNull Env env) {
		return new DatabaseConfig(
			env.requireString(EnvKeys.DB_URL),
			env.requireString(EnvKeys.DB_USER),
			env.requireString(EnvKeys.DB_PASSWORD),
			env.integer(EnvKeys.DB_POOL_SIZE, 10)
		);
	}
	
	public @NonNull String safeUrl() {
		int at = this.url.indexOf('@');
		return at < 0 ? this.url : "jdbc:postgresql://***" + this.url.substring(at);
	}
}
