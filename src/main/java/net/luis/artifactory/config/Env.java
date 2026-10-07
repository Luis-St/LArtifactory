package net.luis.artifactory.config;

import net.luis.utils.math.NumberType;
import net.luis.utils.math.Radix;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.UnaryOperator;

public final class Env {
	
	private final UnaryOperator<String> lookup;
	
	private Env(@NonNull UnaryOperator<String> lookup) {
		this.lookup = lookup;
	}
	
	public static @NonNull Env ofSystem() {
		return new Env(System::getenv);
	}
	
	public static @NonNull Env of(@NonNull Map<String, String> values) {
		return new Env(values::get);
	}
	
	public @NonNull String string(@NonNull String key, @NonNull String defaultValue) {
		Objects.requireNonNull(key, "Key must not be null");
		Objects.requireNonNull(defaultValue, "Default value must not be null");
		
		String value = this.lookup.apply(key);
		return value == null || value.isBlank() ? defaultValue : value;
	}
	
	public @NonNull String requireString(@NonNull String key) {
		Objects.requireNonNull(key, "Key must not be null");
		
		String value = this.lookup.apply(key);
		if (value == null || value.isBlank()) {
			throw new ConfigException("Missing required environment variable: " + key);
		}
		return value;
	}
	
	public @Nullable String optional(@NonNull String key) {
		Objects.requireNonNull(key, "Key must not be null");
		
		String value = this.lookup.apply(key);
		return value == null || value.isBlank() ? null : value;
	}
	
	public boolean bool(@NonNull String key, boolean defaultValue) {
		Objects.requireNonNull(key, "Key must not be null");

		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		return switch (value.strip().toLowerCase(Locale.ROOT)) {
			case "true", "1", "yes", "on" -> true;
			case "false", "0", "no", "off" -> false;
			default -> throw new ConfigException("Environment variable " + key + " must be a boolean, got: " + value);
		};
	}

	public int integer(@NonNull String key, int defaultValue) {
		Objects.requireNonNull(key, "Key must not be null");
		
		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			return NumberType.INTEGER.parseNumber(value.strip(), Radix.DECIMAL);
		} catch (NumberFormatException e) {
			throw new ConfigException("Environment variable " + key + " must be an integer, got: " + value, e);
		}
	}
	
	public int requireInteger(@NonNull String key) {
		Objects.requireNonNull(key, "Key must not be null");
		
		String value = this.optional(key);
		if (value == null) {
			throw new ConfigException("Missing required environment variable: " + key);
		}
		
		try {
			return NumberType.INTEGER.parseNumber(value.strip(), Radix.DECIMAL);
		} catch (NumberFormatException e) {
			throw new ConfigException("Environment variable " + key + " must be an integer, got: " + value, e);
		}
	}
	
	public double decimal(@NonNull String key, double defaultValue) {
		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		
		try {
			return NumberType.DOUBLE.parseNumber(value.strip(), Radix.DECIMAL);
		} catch (NumberFormatException e) {
			throw new ConfigException("Environment variable " + key + " must be a number, got: " + value, e);
		}
	}
	
	public double requireDecimal(@NonNull String key) {
		Objects.requireNonNull(key, "Key must not be null");
		
		String value = this.optional(key);
		if (value == null) {
			throw new ConfigException("Missing required environment variable: " + key);
		}
		
		try {
			return NumberType.DOUBLE.parseNumber(value.strip(), Radix.DECIMAL);
		} catch (NumberFormatException e) {
			throw new ConfigException("Environment variable " + key + " must be a number, got: " + value, e);
		}
	}
	
	public <E extends Enum<E>> @NonNull E enumeration(@NonNull Class<E> clazz, @NonNull String key, @NonNull E defaultValue) {
		Objects.requireNonNull(clazz, "Enum class must not be null");
		Objects.requireNonNull(key, "Key must not be null");
		Objects.requireNonNull(defaultValue, "Default value must not be null");
		
		String value = this.optional(key);
		if (value == null) {
			return defaultValue;
		}
		
		try {
			return Enum.valueOf(clazz, value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ConfigException("Environment variable " + key + " must be one of " + Arrays.toString(clazz.getEnumConstants()) + ", got: " + value, e);
		}
	}
	
	public <E extends Enum<E>> @NonNull E requireEnumeration(@NonNull Class<E> clazz, @NonNull String key) {
		Objects.requireNonNull(clazz, "Enum class must not be null");
		Objects.requireNonNull(key, "Key must not be null");
		
		String value = this.optional(key);
		if (value == null) {
			throw new ConfigException("Missing required environment variable: " + key);
		}
		
		try {
			return Enum.valueOf(clazz, value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new ConfigException("Environment variable " + key + " must be one of " + Arrays.toString(clazz.getEnumConstants()) + ", got: " + value, e);
		}
	}
}
