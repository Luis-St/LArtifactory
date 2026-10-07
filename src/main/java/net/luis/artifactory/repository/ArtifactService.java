package net.luis.artifactory.repository;

import io.javalin.http.Context;
import net.luis.artifactory.database.entity.FileEntity;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.storage.BlobStore;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlDatabase;
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
	
	public @NonNull BlobStore blobStore() {
		return this.blobStore;
	}
	
	public @Nullable FileEntity get(@NonNull String repository, @NonNull String path) throws SqlException {
		return this.database.from(FILES).select().where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PATH, path))).fetchOneOrNull();
	}
	
	public @NonNull List<FileEntity> list(@NonNull String repository, @NonNull String prefix) throws SqlException {
		var query = this.database.from(FILES).select();
		if (prefix.isEmpty()) {
			query = query.where(Sql.equalTo(FILE_REPOSITORY, repository));
		} else {
			query = query.where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.startsWith(FILE_PATH, prefix)));
		}
		return query.orderBy(FILE_PATH.ascending()).fetch().stream().filter(file -> file.path().startsWith(prefix)).toList();
	}
	
	public @NonNull List<FileEntity> listByPackage(@NonNull String repository, @NonNull String packageName) throws SqlException {
		return this.database.from(FILES).select()
			.where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PACKAGE, packageName)))
			.orderBy(FILE_CREATED_AT.ascending())
			.fetch();
	}
	
	public @NonNull List<FileEntity> listByVersion(@NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return this.database.from(FILES).select()
			.where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PACKAGE, packageName), Sql.equalTo(FILE_VERSION, version)))
			.fetch();
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
		FileEntity file = new FileEntity(repository, path, packageName, version, blob.size(), blob.sha256(), blob.sha1(), blob.md5(), blob.sha512(), contentType, metadata, Instant.now(), createdBy);
		FileEntity previous = this.get(repository, path);
		this.database.inTransaction(transaction -> {
			transaction.from(FILES).delete().where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PATH, path))).execute();
			return transaction.from(FILES).insert(file).execute();
		});
		if (previous != null && !previous.sha256().equals(blob.sha256())) {
			this.releaseBlobs(List.of(previous));
		}
		return file;
	}
	
	public boolean delete(@NonNull String repository, @NonNull String path) throws SqlException {
		FileEntity file = this.get(repository, path);
		if (file == null) {
			return false;
		}
		this.database.from(FILES).delete().where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PATH, path))).execute();
		this.releaseBlobs(List.of(file));
		return true;
	}
	
	public int deletePrefix(@NonNull String repository, @NonNull String prefix) throws SqlException {
		List<FileEntity> files = this.list(repository, prefix);
		for (FileEntity file : files) {
			this.database.from(FILES).delete().where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PATH, file.path()))).execute();
		}
		this.releaseBlobs(files);
		return files.size();
	}
	
	public int deleteVersion(@NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		List<FileEntity> files = this.listByVersion(repository, packageName, version);
		this.database.from(FILES).delete().where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PACKAGE, packageName), Sql.equalTo(FILE_VERSION, version))).execute();
		this.releaseBlobs(files);
		return files.size();
	}
	
	/**
	 * Deletes the blobs of the given files if no other file references them anymore.<br>
	 */
	public void releaseBlobs(@NonNull Collection<FileEntity> files) throws SqlException {
		Set<String> digests = new HashSet<>();
		for (FileEntity file : files) {
			digests.add(file.sha256());
		}
		for (String digest : digests) {
			if (!this.database.from(FILES).select().where(Sql.equalTo(FILE_SHA256, digest)).exists()) {
				this.blobStore.delete(digest);
			}
		}
	}
	
	/**
	 * Writes the file to the response, supports HEAD and range requests.<br>
	 */
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
	
	public void serve(@NonNull Context ctx, @NonNull FileEntity file) throws IOException {
		this.serve(ctx, file, file.contentType());
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
	
	public void serve(@NonNull Context ctx, @NonNull FileEntity file, @NonNull String contentType) throws IOException {
		if (!this.blobStore.exists(file.sha256())) {
			throw HttpError.notFound("Content of " + file.path() + " is missing from storage");
		}
		
		String etag = "\"" + file.sha256() + "\"";
		ctx.header("ETag", etag);
		ctx.header("Accept-Ranges", "bytes");
		// Served content is user uploaded, force a download so browsers never render it (e.g. HTML/SVG) in the registry origin.
		ctx.header("Content-Disposition", "attachment" + contentDispositionFilename(file.path()));
		ctx.header("X-Content-Type-Options", "nosniff");
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
			input.skipNBytes(range[0]);
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
}
