package net.luis.artifactory.config;

import org.jspecify.annotations.Nullable;

public class ConfigException extends RuntimeException {
	
	public ConfigException() {}
	
	public ConfigException(@Nullable String message) {
		super(message);
	}
	
	public ConfigException(@Nullable String message, @Nullable Throwable cause) {
		super(message, cause);
	}
	
	public ConfigException(@Nullable Throwable cause) {
		super(cause);
	}
}
