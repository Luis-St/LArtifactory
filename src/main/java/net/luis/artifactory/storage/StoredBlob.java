package net.luis.artifactory.storage;

import org.jspecify.annotations.NonNull;

import java.util.Objects;

public record StoredBlob(
	@NonNull String sha256,
	@NonNull String sha1,
	@NonNull String md5,
	@NonNull String sha512,
	long size
) {
	
	public StoredBlob {
		Objects.requireNonNull(sha256, "Sha256 must not be null");
		Objects.requireNonNull(sha1, "Sha1 must not be null");
		Objects.requireNonNull(md5, "Md5 must not be null");
		Objects.requireNonNull(sha512, "Sha512 must not be null");
	}
	
	public @NonNull String digest(@NonNull String algorithm) {
		return switch (algorithm) {
			case "sha256" -> this.sha256;
			case "sha1" -> this.sha1;
			case "md5" -> this.md5;
			case "sha512" -> this.sha512;
			default -> throw new IllegalArgumentException("Unknown digest algorithm: " + algorithm);
		};
	}
}
