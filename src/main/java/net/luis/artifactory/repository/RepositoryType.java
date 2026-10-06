package net.luis.artifactory.repository;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

public enum RepositoryType {
	
	MAVEN("maven"),
	GENERIC("generic"),
	PYPI("pypi"),
	NPM("npm"),
	NUGET("nuget"),
	CARGO("cargo");
	
	private final String id;
	
	RepositoryType(@NonNull String id) {
		this.id = id;
	}
	
	public static @Nullable RepositoryType byId(@Nullable String id) {
		if (id == null) {
			return null;
		}
		String lower = id.toLowerCase(Locale.ROOT);
		for (RepositoryType type : values()) {
			if (type.id.equals(lower)) {
				return type;
			}
		}
		return null;
	}
	
	public @NonNull String id() {
		return this.id;
	}
}
