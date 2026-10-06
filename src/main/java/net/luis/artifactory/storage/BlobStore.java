package net.luis.artifactory.storage;

import net.luis.artifactory.http.HttpError;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Content addressable blob storage on the local file system.<br>
 * Blobs are stored by their sha256 digest, identical uploads share the same blob.<br>
 */
public class BlobStore {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BlobStore.class);
	private static final HexFormat HEX = HexFormat.of();
	
	private final Path blobRoot;
	private final Path tempRoot;
	private final long maxSize;
	
	public BlobStore(@NonNull Path root, long maxSize) {
		Objects.requireNonNull(root, "Root must not be null");
		this.blobRoot = root.resolve("blobs");
		this.tempRoot = root.resolve("tmp");
		this.maxSize = maxSize;
		
		try {
			Files.createDirectories(this.blobRoot);
			Files.createDirectories(this.tempRoot);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create storage directories in " + root, e);
		}
		LOGGER.info("Blob storage initialized at {}", root);
	}
	
	public long maxSize() {
		return this.maxSize;
	}
	
	public @NonNull StoredBlob store(@NonNull InputStream input) throws IOException {
		Objects.requireNonNull(input, "Input must not be null");
		
		MessageDigest sha256 = digest("SHA-256");
		MessageDigest sha1 = digest("SHA-1");
		MessageDigest md5 = digest("MD5");
		MessageDigest sha512 = digest("SHA-512");
		
		Path temp = Files.createTempFile(this.tempRoot, "upload-", ".tmp");
		long size = 0;
		try {
			try (OutputStream output = Files.newOutputStream(temp)) {
				byte[] buffer = new byte[64 * 1024];
				int read;
				while ((read = input.read(buffer)) != -1) {
					size += read;
					if (size > this.maxSize) {
						throw HttpError.payloadTooLarge();
					}
					sha256.update(buffer, 0, read);
					sha1.update(buffer, 0, read);
					md5.update(buffer, 0, read);
					sha512.update(buffer, 0, read);
					output.write(buffer, 0, read);
				}
			}
			
			StoredBlob blob = new StoredBlob(HEX.formatHex(sha256.digest()), HEX.formatHex(sha1.digest()), HEX.formatHex(md5.digest()), HEX.formatHex(sha512.digest()), size);
			Path target = this.path(blob.sha256());
			if (!Files.exists(target)) {
				Files.createDirectories(target.getParent());
				try {
					Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
				} catch (FileAlreadyExistsException ignored) {}
			}
			return blob;
		} finally {
			Files.deleteIfExists(temp);
		}
	}
	
	public @NonNull StoredBlob store(byte @NonNull [] data) throws IOException {
		return this.store(new ByteArrayInputStream(data));
	}
	
	public boolean exists(@NonNull String sha256) {
		return Files.exists(this.path(sha256));
	}
	
	public @NonNull InputStream open(@NonNull String sha256) throws IOException {
		return Files.newInputStream(this.path(sha256));
	}
	
	public byte @NonNull [] read(@NonNull String sha256) throws IOException {
		return Files.readAllBytes(this.path(sha256));
	}
	
	public @NonNull Path path(@NonNull String sha256) {
		if (sha256.length() != 64 || !sha256.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
			throw new IllegalArgumentException("Invalid sha256: " + sha256);
		}
		return this.blobRoot.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
	}
	
	public void delete(@NonNull String sha256) {
		try {
			Files.deleteIfExists(this.path(sha256));
		} catch (IOException e) {
			LOGGER.warn("Failed to delete blob {}", sha256, e);
		}
	}
	
	private static @NonNull MessageDigest digest(@NonNull String algorithm) {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Missing digest algorithm " + algorithm, e);
		}
	}
	
	public static @NonNull String sha256Hex(@NonNull String value) {
		return HEX.formatHex(digest("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
}
