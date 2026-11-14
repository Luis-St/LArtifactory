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
import net.luis.utils.io.database.expression.SqlExpression;
import net.luis.utils.io.database.type.SqlTypes;
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
	
	/**
	 * Maximum length of a file path, the length of the path column.<br>
	 */
	private static final int MAX_PATH_LENGTH = 1024;
	/**
	 * Marks a range header that is valid but can not be satisfied, answered with 416.<br>
	 */
	private static final long[] UNSATISFIABLE = new long[0];
	private final SqlDatabase database;
	private final BlobStore blobStore;
	private final WebhookService webhooks;
	private final AuditService audit;
	
	public ArtifactService(@NonNull SqlDatabase database, @NonNull BlobStore blobStore, @NonNull WebhookService webhooks, @NonNull AuditService audit) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
		this.blobStore = Objects.requireNonNull(blobStore, "Blob store must not be null");
		this.webhooks = Objects.requireNonNull(webhooks, "Webhook service must not be null");
		this.audit = Objects.requireNonNull(audit, "Audit service must not be null");
	}
	
	/**
	 * Notifies the webhooks and records the change in the audit log once the transaction was committed.<br>
	 */
	private void changed(@NonNull UnitOfWork work, @NonNull String event, @NonNull String action, @NonNull String repository, @NonNull String target, @Nullable String actor) {
		work.afterCommit(() -> {
			this.webhooks.dispatch(event, repository, target);
			this.audit.record(actor, action, repository, target, null, null);
		});
	}
	
	@Nullable
	private static FileEntity get(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String path) throws SqlException {
		return sql.from(FILES).select().where(fileCondition(repository, path)).fetchOneOrNull();
	}
	
	@NonNull
	private static List<FileEntity> list(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String prefix, long offset, long limit) throws SqlException {
		var query = sql.from(FILES).select().where(prefixCondition(repository, prefix)).orderBy(FILE_PATH.ascending());
		if (offset > 0) {
			query = query.offset(offset);
		}
		if (limit < Long.MAX_VALUE) {
			query = query.limit(limit);
		}
		return query.fetch().stream().filter(file -> file.path().startsWith(prefix)).toList();
	}
	
	@NonNull
	private static List<FileEntity> listByVersion(@NonNull SqlProvider sql, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return sql.from(FILES).select().where(versionCondition(repository, packageName, version)).fetch();
	}
	
	@NonNull
	private static SqlCondition fileCondition(@NonNull String repository, @NonNull String path) {
		return SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PATH, path));
	}
	
	@NonNull
	private static SqlCondition prefixCondition(@NonNull String repository, @NonNull String prefix) {
		if (prefix.isEmpty()) {
			return Sql.equalTo(FILE_REPOSITORY, repository);
		}
		return SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Conditions.startsWith(FILE_PATH, prefix));
	}
	
	@NonNull
	private static SqlCondition versionCondition(@NonNull String repository, @NonNull String packageName, @NonNull String version) {
		return SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PACKAGE, packageName), Sql.equalTo(FILE_VERSION, version));
	}
	
	/**
	 * Parses a single byte range header.<br>
	 *
	 * @return The first and last byte of the range, null if the header is absent or unsupported (the full file is sent)
	 * and {@link #UNSATISFIABLE} if no byte of the range is inside the file
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
				if (suffix < 0) {
					return null;
				}
				if (suffix == 0) {
					return UNSATISFIABLE;
				}
				start = Math.max(0, size - suffix);
				end = size - 1;
			} else {
				start = Long.parseLong(startText);
				long last = endText.isEmpty() ? Long.MAX_VALUE : Long.parseLong(endText);
				if (start < 0 || last < start) {
					return null; // syntactically invalid, the header is ignored
				}
				if (start >= size) {
					return UNSATISFIABLE;
				}
				end = Math.min(last, size - 1);
			}
			return new long[] { start, end };
		} catch (NumberFormatException e) {
			return null;
		}
	}
	
	/**
	 * Builds the {@code filename} part of a {@code Content-Disposition} header, escaping it against header injection.<br>
	 */
	@NonNull
	private static String contentDispositionFilename(@NonNull String path) {
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
	
	@NonNull
	public BlobStore blobStore() {
		return this.blobStore;
	}
	
	/**
	 * Runs the action in one database transaction, the blobs of files removed by it are released and the actions registered
	 * for after the commit (e.g. webhooks) are run once the transaction was committed.<br>
	 */
	public <T> T transaction(@NonNull ThrowableFunction<UnitOfWork, T, SqlException> action) throws SqlException {
		List<FileEntity> removed = new ArrayList<>();
		List<Runnable> afterCommit = new ArrayList<>();
		T result = this.database.inTransaction(transaction -> {
			UnitOfWork work = new UnitOfWork(transaction);
			T value = action.apply(work);
			removed.addAll(work.removedFiles());
			afterCommit.addAll(work.afterCommitActions());
			return value;
		});
		this.releaseBlobs(removed);
		afterCommit.forEach(Runnable::run);
		return result;
	}
	
	@Nullable
	public FileEntity get(@NonNull String repository, @NonNull String path) throws SqlException {
		return get(this.database, repository, path);
	}
	
	@Nullable
	public FileEntity get(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String path) throws SqlException {
		return get(work.sql(), repository, path);
	}
	
	@NonNull
	public List<FileEntity> list(@NonNull String repository, @NonNull String prefix) throws SqlException {
		return list(this.database, repository, prefix, 0, Long.MAX_VALUE);
	}
	
	/**
	 * Lists one page of the files below the prefix, ordered by path.<br>
	 */
	@NonNull
	public List<FileEntity> list(@NonNull String repository, @NonNull String prefix, long offset, long limit) throws SqlException {
		return list(this.database, repository, prefix, offset, limit);
	}
	
	public long count(@NonNull String repository, @NonNull String prefix) throws SqlException {
		return this.database.from(FILES).select().where(prefixCondition(repository, prefix)).count();
	}
	
	/**
	 * Lists the direct content of a directory, the subdirectories are computed by the database so a listing does not load the whole subtree.<br>
	 *
	 * @param prefix The path of the directory with a trailing slash, empty for the root
	 */
	@NonNull
	public DirectoryListing listDirectory(@NonNull String repository, @NonNull String prefix) throws SqlException {
		// The part of the path below the directory and the position of its first slash (0 for files directly in the directory)
		SqlExpression<String> rest = Sql.substring(FILE_PATH, prefix.codePointCount(0, prefix.length()) + 1, MAX_PATH_LENGTH);
		SqlExpression<Integer> slash = Sql.position(rest, Sql.of("/", SqlTypes.TEXT), SqlTypes.INTEGER);
		
		List<String> directories = this.database.from(FILES).select(Sql.left(rest, slash))
			.where(SqlCondition.allOf(prefixCondition(repository, prefix), Sql.greaterThan(slash, 0)))
			.distinct()
			.fetch();
		List<FileEntity> files = this.database.from(FILES).select()
			.where(SqlCondition.allOf(prefixCondition(repository, prefix), Sql.equalTo(slash, 0)))
			.orderBy(FILE_PATH.ascending())
			.fetch();
		return new DirectoryListing(new TreeSet<>(directories), files.stream().filter(file -> file.path().startsWith(prefix)).toList());
	}
	
	@NonNull
	public List<FileEntity> listByPackage(@NonNull String repository, @NonNull String packageName) throws SqlException {
		return this.database.from(FILES).select()
			.where(SqlCondition.allOf(Sql.equalTo(FILE_REPOSITORY, repository), Sql.equalTo(FILE_PACKAGE, packageName)))
			.orderBy(FILE_CREATED_AT.ascending())
			.fetch();
	}
	
	@NonNull
	public List<FileEntity> listByVersion(@NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return listByVersion(this.database, repository, packageName, version);
	}
	
	@NonNull
	public List<FileEntity> listByVersion(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		return listByVersion(work.sql(), repository, packageName, version);
	}
	
	@NonNull
	public StoredBlob upload(@NonNull InputStream input) throws IOException {
		return this.blobStore.store(input);
	}
	
	@NonNull
	public StoredBlob upload(byte @NonNull [] data) throws IOException {
		return this.blobStore.store(data);
	}
	
	/**
	 * Records a file in the repository, replacing an existing file at the same path.<br>
	 * A file at the path that was committed concurrently is not replaced, the insert fails with a unique violation instead.<br>
	 */
	@NonNull
	public FileEntity put(@NonNull String repository, @NonNull String path, @NonNull StoredBlob blob, @NonNull String contentType, @Nullable String packageName, @Nullable String version, @Nullable String metadata, @Nullable String createdBy) throws SqlException {
		return this.transaction(work -> this.put(work, repository, path, blob, contentType, packageName, version, metadata, createdBy));
	}
	
	@NonNull
	public FileEntity put(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String path, @NonNull StoredBlob blob, @NonNull String contentType, @Nullable String packageName, @Nullable String version, @Nullable String metadata, @Nullable String createdBy) throws SqlException {
		return this.put(work, repository, path, blob, contentType, packageName, version, metadata, createdBy, true, true);
	}
	
	/**
	 * Records a file at a path the caller found empty in the same transaction, without replacing anything.<br>
	 * A file committed at the path in between makes the insert fail with a unique violation, so the unique key decides
	 * a race of uploads to an immutable path instead of the later upload silently replacing the earlier one.<br>
	 */
	@NonNull
	public FileEntity insert(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String path, @NonNull StoredBlob blob, @NonNull String contentType, @Nullable String packageName, @Nullable String version, @Nullable String metadata, @Nullable String createdBy) throws SqlException {
		return this.put(work, repository, path, blob, contentType, packageName, version, metadata, createdBy, true, false);
	}
	
	/**
	 * Records a cached copy of an artifact fetched from a remote repository, without firing a publish webhook.<br>
	 */
	@NonNull
	public FileEntity putCached(@NonNull String repository, @NonNull String path, @NonNull StoredBlob blob, @NonNull String contentType) throws SqlException {
		return this.transaction(work -> this.put(work, repository, path, blob, contentType, null, null, null, "remote-cache", false, true));
	}
	
	@NonNull
	private FileEntity put(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String path, @NonNull StoredBlob blob, @NonNull String contentType, @Nullable String packageName, @Nullable String version, @Nullable String metadata, @Nullable String createdBy, boolean notify, boolean replace) throws SqlException {
		FileEntity file = new FileEntity(repository, path, packageName, version, blob.size(), blob.sha256(), blob.sha1(), blob.md5(), blob.sha512(), contentType, metadata, Instant.now(), createdBy);
		FileEntity previous = replace ? get(work.sql(), repository, path) : null;
		// Only the file read here is replaced: a file a concurrent upload committed after this read is not deleted,
		// the insert then fails with a unique violation instead of silently overwriting an immutable path
		if (previous != null) {
			work.sql().from(FILES).delete().where(fileCondition(repository, path)).execute();
		}
		work.sql().from(FILES).insert(file).execute();
		if (previous != null && !previous.sha256().equals(blob.sha256())) {
			work.removed(List.of(previous));
		}
		if (notify) {
			this.changed(work, WebhookService.ARTIFACT_PUBLISHED, AuditService.ARTIFACT_PUBLISH, repository, path, createdBy);
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
		this.changed(work, WebhookService.ARTIFACT_DELETED, AuditService.ARTIFACT_DELETE, repository, path, null);
		return true;
	}
	
	@NonNull
	public List<FileEntity> deletePrefix(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String prefix) throws SqlException {
		List<FileEntity> files = list(work.sql(), repository, prefix, 0, Long.MAX_VALUE);
		for (FileEntity file : files) {
			work.sql().from(FILES).delete().where(fileCondition(repository, file.path())).execute();
		}
		work.removed(files);
		if (!files.isEmpty()) {
			this.changed(work, WebhookService.ARTIFACT_DELETED, AuditService.ARTIFACT_DELETE, repository, prefix, null);
		}
		return files;
	}
	
	public int deleteVersion(@NonNull UnitOfWork work, @NonNull String repository, @NonNull String packageName, @NonNull String version) throws SqlException {
		List<FileEntity> files = listByVersion(work.sql(), repository, packageName, version);
		work.sql().from(FILES).delete().where(versionCondition(repository, packageName, version)).execute();
		work.removed(files);
		if (!files.isEmpty()) {
			this.changed(work, WebhookService.ARTIFACT_DELETED, AuditService.ARTIFACT_DELETE, repository, packageName + "@" + version, null);
		}
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
		ctx.header("Last-Modified", Requests.httpDate(file.createdAt()));
		
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
		if (range == UNSATISFIABLE) {
			ctx.status(416).contentType(contentType);
			ctx.header("Content-Range", "bytes */" + file.size());
			ctx.result("");
			return;
		}
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
	
	/**
	 * The direct content of a directory.<br>
	 *
	 * @param directories The names of the subdirectories with a trailing slash, sorted
	 * @param files The files directly in the directory, sorted by path
	 */
	public record DirectoryListing(@NonNull SortedSet<String> directories, @NonNull List<FileEntity> files) {
		
		public boolean isEmpty() {
			return this.directories.isEmpty() && this.files.isEmpty();
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
}
