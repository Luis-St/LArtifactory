package net.luis.artifactory.auth;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/**
 * Access levels on a repository, each level includes all lower levels.<br>
 * <ul>
 *     <li>{@link #READ}: resolve and download packages</li>
 *     <li>{@link #WRITE}: publish packages, yank/unlist versions and manage tags</li>
 *     <li>{@link #DELETE}: delete artifacts and unpublish packages</li>
 * </ul>
 */
public enum AccessLevel {
	
	READ, WRITE, DELETE;
	
	@Nullable
	public static AccessLevel parse(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return valueOf(value.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
	
	@NonNull
	public static AccessLevel min(@NonNull AccessLevel first, @NonNull AccessLevel second) {
		return first.ordinal() <= second.ordinal() ? first : second;
	}
	
	public boolean includes(@NonNull AccessLevel other) {
		return this.ordinal() >= other.ordinal();
	}
}
