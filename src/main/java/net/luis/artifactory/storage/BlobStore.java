package net.luis.artifactory.storage;

import com.google.common.util.concurrent.Striped;
import net.luis.artifactory.http.HttpError;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.stream.Stream;

/**
 * Content addressable blob storage on the local file system.<br>
 * Blobs are stored by their sha256 digest, identical uploads share the same blob.<br>
 * <p>
 *     A stored blob is only referenced once the database row of its file is committed, which happens after {@link #store(InputStream)} returned.
 *     To not delete a blob that a concurrent upload of the same content is about to reference, storing a blob refreshes its modification time
 *     and unreferenced blobs are only deleted once they were not stored for the orphan grace period. Both happen under a lock per digest.
 *     Blobs that were skipped because of the grace period are removed later by {@link #sweepOrphans(ReferenceCheck)}.
 * </p>
 */
public class BlobStore {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(BlobStore.class);
	private static final HexFormat HEX = HexFormat.of();
	
	private final Path blobRoot;
	private final Path tempRoot;
	private final long maxSize;
	private final Duration orphanGracePeriod;
	private final Striped<Lock> locks = Striped.lock(64);
	
	public BlobStore(@NonNull Path root, long maxSize, @NonNull Duration orphanGracePeriod) {
		Objects.requireNonNull(root, "Root must not be null");
		this.blobRoot = root.resolve("blobs");
		this.tempRoot = root.resolve("tmp");
		this.maxSize = maxSize;
		this.orphanGracePeriod = Objects.requireNonNull(orphanGracePeriod, "Orphan grace period must not be null");
		
		try {
			Files.createDirectories(this.blobRoot);
			Files.createDirectories(this.tempRoot);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create storage directories in " + root, e);
		}
		LOGGER.info("Blob storage initialized at {}", root);
	}
	
	private static boolean isDigest(@NonNull String value) {
		return value.length() == 64 && value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
	}
	
	@NonNull
	private static MessageDigest digest(@NonNull String algorithm) {
		try {
			return MessageDigest.getInstance(algorithm);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Missing digest algorithm " + algorithm, e);
		}
	}
	
	@NonNull
	public static String sha256Hex(@NonNull String value) {
		return HEX.formatHex(digest("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
	
	public long maxSize() {
		return this.maxSize;
	}
	
	@NonNull
	public StoredBlob store(@NonNull InputStream input) throws IOException {
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
			Lock lock = this.locks.get(blob.sha256());
			lock.lock();
			try {
				if (Files.exists(target)) {
					// Restart the grace period, the existing blob is about to be referenced by this upload
					Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
				} else {
					Files.createDirectories(target.getParent());
					Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
				}
			} finally {
				lock.unlock();
			}
			return blob;
		} finally {
			Files.deleteIfExists(temp);
		}
	}
	
	@NonNull
	public StoredBlob store(byte @NonNull [] data) throws IOException {
		return this.store(new ByteArrayInputStream(data));
	}
	
	/**
	 * Creates an empty file in the temporary directory, it is deleted by {@link #cleanTemporaryFiles(Duration)} once it was not modified for a while.<br>
	 */
	@NonNull
	public Path createTemporaryFile(@NonNull String prefix) throws IOException {
		return Files.createTempFile(this.tempRoot, prefix, ".tmp");
	}
	
	public boolean exists(@NonNull String sha256) {
		return Files.exists(this.path(sha256));
	}
	
	@NonNull
	public InputStream open(@NonNull String sha256) throws IOException {
		return Files.newInputStream(this.path(sha256));
	}
	
	public byte @NonNull [] read(@NonNull String sha256) throws IOException {
		return Files.readAllBytes(this.path(sha256));
	}
	
	@NonNull
	public Path path(@NonNull String sha256) {
		if (!isDigest(sha256)) {
			throw new IllegalArgumentException("Invalid sha256: " + sha256);
		}
		return this.blobRoot.resolve(sha256.substring(0, 2)).resolve(sha256.substring(2, 4)).resolve(sha256);
	}
	
	/**
	 * Deletes the blob if it is not referenced and was not stored within the orphan grace period.<br>
	 *
	 * @return true if the blob was deleted
	 */
	public <X extends Exception> boolean deleteIfUnreferenced(@NonNull String sha256, @NonNull ReferenceCheck<X> check) throws X {
		Path path = this.path(sha256);
		Lock lock = this.locks.get(sha256);
		lock.lock();
		try {
			if (!Files.exists(path) || this.withinGracePeriod(path) || check.isReferenced(sha256)) {
				return false;
			}
			Files.deleteIfExists(path);
			return true;
		} catch (IOException e) {
			LOGGER.warn("Failed to delete blob {}", sha256, e);
			return false;
		} finally {
			lock.unlock();
		}
	}
	
	/**
	 * Deletes all blobs which are not referenced and older than the orphan grace period.<br>
	 *
	 * @return The number of deleted blobs
	 */
	public <X extends Exception> int sweepOrphans(@NonNull ReferenceCheck<X> check) throws X {
		int deleted = 0;
		try (Stream<Path> paths = Files.walk(this.blobRoot)) {
			for (Path path : (Iterable<Path>) paths::iterator) {
				String name = path.getFileName().toString();
				if (Files.isRegularFile(path) && isDigest(name) && this.deleteIfUnreferenced(name, check)) {
					deleted++;
				}
			}
		} catch (IOException | UncheckedIOException e) {
			LOGGER.warn("Failed to sweep orphaned blobs", e);
		}
		return deleted;
	}
	
	/**
	 * Deletes temporary files (interrupted uploads, multipart caches) which were not modified for the given duration.<br>
	 *
	 * @return The number of deleted files
	 */
	public int cleanTemporaryFiles(@NonNull Duration maxAge) {
		Instant threshold = Instant.now().minus(maxAge);
		int deleted = 0;
		try (Stream<Path> paths = Files.list(this.tempRoot)) {
			for (Path path : (Iterable<Path>) paths::iterator) {
				try {
					if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toInstant().isBefore(threshold) && Files.deleteIfExists(path)) {
						deleted++;
					}
				} catch (IOException e) {
					LOGGER.warn("Failed to delete temporary file {}", path, e);
				}
			}
		} catch (IOException | UncheckedIOException e) {
			LOGGER.warn("Failed to clean temporary files", e);
		}
		return deleted;
	}
	
	private boolean withinGracePeriod(@NonNull Path path) throws IOException {
		return Files.getLastModifiedTime(path).toInstant().isAfter(Instant.now().minus(this.orphanGracePeriod));
	}
	
	/**
	 * Checks whether a blob is still referenced by a stored file.<br>
	 */
	@FunctionalInterface
	public interface ReferenceCheck<X extends Exception> {
		
		boolean isReferenced(@NonNull String sha256) throws X;
	}
}
