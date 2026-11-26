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
	CARGO("cargo"),
	OCI("oci"),
	DEBIAN("debian");
	
	private final String id;
	
	RepositoryType(@NonNull String id) {
		this.id = id;
	}
	
	@Nullable
	public static RepositoryType byId(@Nullable String id) {
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
	
	@NonNull
	public String id() {
		return this.id;
	}
}
