package net.luis.artifactory.repository;

import io.javalin.http.Context;
import net.luis.artifactory.database.Conditions;
import net.luis.artifactory.database.UnitOfWork;
import net.luis.artifactory.database.entity.FileEntity;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.storage.BlobStore;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.utils.function.throwable.ThrowableFunction;
import net.luis.utils.io.database.*;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

import static net.luis.artifactory.database.Tables.*;

/**
 * Stores and serves the files of all repositories, file contents live in the {@link BlobStore}.<br>
 */
public class ArtifactService {
	
	private final SqlDatabase database;
	private final BlobStore blobStore;
	
	public ArtifactService(@NonNull SqlDatabase database, @NonNull BlobStore blobStore) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
		this.blobStore = Objects.requireNonNull(blobStore, "Blob store must not be null");
	}
	
	private static @Nullable FileEntity get(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String path) throws SqlException {
		return sql.from(FILES).select().where(fileCondition(repository, path)).fetchOneOrNull();
	}
	
	private static @NonNull List<FileEntity> list(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String prefix, long offset, long limit) throws SqlException {
		var query = sql.from(FILES).select().where(prefixCondition(repository, prefix)).orderBy(FILE_PATH.ascending());
		if (offset > 0) {
			query = query.offset(offset);
		}
		if (limit < Long.MAX_VALUE) {
			query = query.limit(limit);
		}
		return query.fetch().stream().filter(file -> file.path().startsWith(prefix)).toList();
	}
	
	private static @NonNull List<FileEntity> listByVersion(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return sql.from(FILES).select().where(versionCondition(repository, packageName, version)).fetch();
	}
	
	private static @NonNull SqlCondition fileCondition(@NonNull String repository, @NonNull String path) {
		return SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PATH, path));
	}
	
	private static @NonNull SqlCondition prefixCondition(@NonNull String repository, @NonNull String prefix) {
		if (prefix.isEmpty()) {
			return Sql.equalTo(FILE_REPOSITORY, repository);
		}
		return SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Conditions.startsWith(FILE_PATH, prefix));
	}
	
	private static @NonNull SqlCondition versionCondition(@NonNull String repository, @NonNull String packageName, @NonNull String version) {
		return SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PACKAGE, packageName), Sql.equalTo(FILE_VERSION, version));
	}
	
	/**
	 * Parses a single byte range header, returns null if absent, unsupported or not satisfiable.<br>
	 */
	private static long @Nullable [] parseRange(@Nullable String header, long size) {
		if (header == null || !header.startsWith("bytes=") || header.contains(",") || size == 0) {
			return null;
		}
		String spec = header.substring("bytes=".length()).strip();
		int dash = spec.indexOf('-');
		if (dash < 0) {
			return null;
		}
		try {
			String startText = spec.substring(0, dash).strip();
			String endText = spec.substring(dash + 1).strip();
			long start;
			long end;
			if (startText.isEmpty()) {
				long suffix = Long.parseLong(endText);
				start = Math.max(0, size - suffix);
				end = size - 1;
			} else {
				start = Long.parseLong(startText);
				end = endText.isEmpty() ? size - 1 : Math.min(Long.parseLong(endText), size - 1);
			}
			return start <= end && start < size ? new long[] { start, end } : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	/**
	 * Builds the {@code filename} part of a {@code Content-Disposition} header, escaping it against header injection.<br>
	 */
	private static @NonNull String contentDispositionFilename(@NonNull String path) {
		String name = path.substring(path.lastIndexOf('/') + 1);
		StringBuilder ascii = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
		}
		if (ascii.isEmpty()) {
			return "";
		}
		String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
		return "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
	}
	
	public @NonNull BlobStore blobStore() {
		return this.blobStore;
	}
	
	/**
	 * Runs the action in one database transaction, the blobs of files removed by it are released after the commit.<br>
	 */
	public <T> T transaction(@NonNull ThrowableFunction<UnitOfWork, T, SqlException> action) throws SqlException {
		List<FileEntity> removed = new ArrayList<>();
		T result = this.database.inTransaction(transaction -> {
			UnitOfWork work = new UnitOfWork(transaction);
			T value = action.apply(work);
			removed.addAll(work.removedFiles());
			return value;
		});
		this.releaseBlobs(removed);
		return result;
	}
	
	public @Nullable FileEntity get(@NonNull String repository, @NonNull String path) throws SqlException {
		return get(this.database, repository, path);
	}
	
	public @Nullable FileEntity get(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String path) throws SqlException {
		return get(work.sql(), repository, path);
	}
	
	public @NonNull List<FileEntity> list(@NonNull String repository, @NonNull String prefix) throws SqlException {
		return list(this.database, repository, prefix, 0, Long.MAX_VALUE);
	}
	
	/**
	 * Lists one page of the files below the prefix, ordered by path.<br>
	 */
	public @NonNull List<FileEntity> list(@NonNull String repository, @NonNull String prefix, long offset, long limit) throws SqlException {
		return list(this.database, repository, prefix, offset, limit);
	}
	
	public long count(@NonNull String repository, @NonNull String prefix) throws SqlException {
		return this.database.from(FILES).select().where(prefixCondition(repository, prefix)).count();
	}
	
	public @NonNull List<FileEntity> listByPackage(@NonNull String repository, @NonNull String packageName) throws SqlException {
		return this.database.from(FILES).select()
			.where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PACKAGE, packageName)))
			.orderBy(FILE_CREATED_AT.ascending())
			.fetch();
	}
	
	public @NonNull List<FileEntity> listByVersion(@NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return listByVersion(this.database, repository, packageName, version);
	}
	
	public @NonNull List<FileEntity> listByVersion(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return listByVersion(work.sql(), repository, packageName, version);
	}
	
	public @NonNull StoredBlob upload(@NonNull InputStream input) throws IOException {
		return this.blobStore.store(input);
	}
	
	public @NonNull StoredBlob upload(byte @NonNull [] data) throws IOException {
		return this.blobStore.store(data);
	}
	
	/**
	 * Records a file in the repository, replacing an existing file at the same path.<br>
	 */
	public @NonNull FileEntity put(@NonNull String repository, @NonNull String path, @NonNull StoredBlob blob, @NonNull String contentType, @Nullable String packageName, @Nullable String version, @Nullable String metadata, @Nullable String createdBy) throws SqlException {
		return this.transaction(work -> this.put(work, repository, path, blob, contentType, packageName, version, metadata, createdBy));
	}
	
	public @NonNull FileEntity put(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String path, @NonNull StoredBlob blob, @NonNull String contentType, @Nullable String packageName, @Nullable String version, @Nullable String metadata, @Nullable String createdBy) throws SqlException {
		FileEntity file = new FileEntity(repository, path, packageName, version, blob.size(), blob.sha256(), blob.sha1(), blob.md5(), blob.sha512(), contentType, metadata, Instant.now(), createdBy);
		FileEntity previous = get(work.sql(), repository, path);
		work.sql().from(FILES).delete().where(fileCondition(repository, path)).execute();
		work.sql().from(FILES).insert(file).execute();
		if (previous != null && !previous.sha256().equals(blob.sha256())) {
			work.removed(List.of(previous));
		}
		return file;
	}
	
	public boolean delete(@NonNull String repository, @NonNull String path) throws SqlException {
		return this.transaction(work -> this.delete(work, repository, path));
	}
	
	public boolean delete(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String path) throws SqlException {
		FileEntity file = get(work.sql(), repository, path);
		if (file == null) {
			return false;
		}
		work.sql().from(FILES).delete().where(fileCondition(repository, path)).execute();
		work.removed(List.of(file));
		return true;
	}
	
	public @NonNull List<FileEntity> deletePrefix(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String prefix) throws SqlException {
		List<FileEntity> files = list(work.sql(), repository, prefix, 0, Long.MAX_VALUE);
		for (FileEntity file : files) {
			work.sql().from(FILES).delete().where(fileCondition(repository, file.path())).execute();
		}
		work.removed(files);
		return files;
	}
	
	public int deleteVersion(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		List<FileEntity> files = listByVersion(work.sql(), repository, packageName, version);
		work.sql().from(FILES).delete().where(versionCondition(repository, packageName, version)).execute();
		work.removed(files);
		return files.size();
	}
	
	/**
	 * Deletes the blobs of the given files if no other file references them anymore.<br>
	 * Blobs stored within the orphan grace period are kept, they may belong to an upload that is not committed yet.<br>
	 */
	public void releaseBlobs(@NonNull Collection<FileEntity> files) throws SqlException {
		Set<String> digests = new HashSet<>();
		for (FileEntity file : files) {
			digests.add(file.sha256());
		}
		for (String digest : digests) {
			this.blobStore.deleteIfUnreferenced(digest, this::isReferenced);
		}
	}
	
	/**
	 * Deletes the blobs that no file references, e.g. rejected uploads or blobs skipped by {@link #releaseBlobs(Collection)}.<br>
	 *
	 * @return The number of deleted blobs
	 */
	public int sweepOrphanedBlobs() throws SqlException {
		return this.blobStore.sweepOrphans(this::isReferenced);
	}
	
	private boolean isReferenced(@NonNull String sha256) throws SqlException {
		return this.database.from(FILES).select().where(Sql.equalTo(FILE_SHA256, sha256)).exists();
	}
	
	public void serve(@NonNull Context ctx, @NonNull FileEntity file) throws IOException {
		this.serve(ctx, file, file.contentType());
	}
	
	/**
	 * Writes the file to the response, supports HEAD and range requests.<br>
	 */
	public void serve(@NonNull Context ctx, @NonNull FileEntity file, @NonNull String contentType) throws IOException {
		if (!this.blobStore.exists(file.sha256())) {
			throw HttpError.notFound("Content of " + file.path() + " is missing from storage");
		}
		
		String etag = "\"" + file.sha256() + "\"";
		ctx.header("ETag", etag);
		ctx.header("Accept-Ranges", "bytes");
		// Serve stored artifacts as downloads, not inline, so an uploaded html/svg can not execute script in this origin
		ctx.header("X-Content-Type-Options", "nosniff");
		ctx.header("Content-Disposition", "attachment" + contentDispositionFilename(file.path()));
		ctx.header("X-Checksum-Sha1", file.sha1());
		ctx.header("X-Checksum-Sha256", file.sha256());
		ctx.header("X-Checksum-Md5", file.md5());
		ctx.header("Last-Modified", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(file.createdAt().atZone(java.time.ZoneOffset.UTC)));
		
		if (etag.equals(ctx.header("If-None-Match"))) {
			ctx.status(304);
			return;
		}
		if (Requests.isHead(ctx)) {
			ctx.contentType(contentType);
			ctx.header("Content-Length", String.valueOf(file.size()));
			ctx.status(200);
			return;
		}
		long[] range = parseRange(ctx.header("Range"), file.size());
		if (range != null) {
			long length = range[1] - range[0] + 1;
			InputStream input = this.blobStore.open(file.sha256());
			try {
				input.skipNBytes(range[0]);
			} catch (IOException e) {
				input.close();
				throw e;
			}
			ctx.status(206).contentType(contentType);
			ctx.header("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + file.size());
			ctx.header("Content-Length", String.valueOf(length));
			ctx.result(new BoundedInputStream(input, length));
			return;
		}
		ctx.contentType(contentType);
		ctx.header("Content-Length", String.valueOf(file.size()));
		ctx.result(this.blobStore.open(file.sha256()));
	}
	
	private static final class BoundedInputStream extends java.io.FilterInputStream {
		
		private long remaining;
		
		private BoundedInputStream(@NonNull InputStream input, long limit) {
			super(input);
			this.remaining = limit;
		}
		
		@Override
		public int read() throws IOException {
			if (this.remaining <= 0) {
				return -1;
			}
			int value = super.read();
			if (value >= 0) {
				this.remaining--;
			}
			return value;
		}
		
		@Override
		public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
			if (this.remaining <= 0) {
				return -1;
			}
			int read = super.read(buffer, offset, (int) Math.min(length, this.remaining));
			if (read > 0) {
				this.remaining -= read;
			}
			return read;
		}
	}
}
