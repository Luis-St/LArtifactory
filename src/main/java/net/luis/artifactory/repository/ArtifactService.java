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
	public void serve(@NonNull Context ctx, @NonNull FileEntity file) throws IOException {
		this.serve(ctx, file, file.contentType());
	}
	
	public void serve(@NonNull Context ctx, @NonNull FileEntity file, @NonNull String contentType) throws IOException {
		if (!this.blobStore.exists(file.sha256())) {
			throw HttpError.notFound("Content of " + file.path() + " is missing from storage");
		}
		
		String etag = "\"" + file.sha256() + "\"";
		ctx.header("ETag", etag);
		ctx.header("Accept-Ranges", "bytes");
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
		if (ctx.header("Range") != null) {
			ctx.writeSeekableStream(this.blobStore.open(file.sha256()), contentType, file.size());
			return;
		}
		ctx.contentType(contentType);
		ctx.header("Content-Length", String.valueOf(file.size()));
		ctx.result(this.blobStore.open(file.sha256()));
	}
}
