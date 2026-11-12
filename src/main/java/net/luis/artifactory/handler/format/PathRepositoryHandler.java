package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.SqlErrors;
import net.luis.artifactory.database.UnitOfWork;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.*;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Base handler of path based repositories (Maven and generic) supporting GET, HEAD, PUT and DELETE of arbitrary paths.<br>
 */
public abstract class PathRepositoryHandler extends FormatHandler {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(PathRepositoryHandler.class);
	private static final DateTimeFormatter LISTING_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
	protected static final List<String> CHECKSUM_EXTENSIONS = List.of("sha1", "md5", "sha256", "sha512");
	
	protected PathRepositoryHandler(@NonNull Services services) {
		super(services);
	}
	
	protected static void serveGenerated(@NonNull Context ctx, @NonNull String path, @NonNull String content, @NonNull String contentType) {
		byte[] data = content.getBytes(StandardCharsets.UTF_8);
		String checksum = checksumExtension(path);
		if (checksum != null) {
			data = digestHex(checksum, data).getBytes(StandardCharsets.UTF_8);
			contentType = "text/plain";
		}
		ctx.status(200).contentType(contentType).header("Content-Length", String.valueOf(data.length));
		if (!Requests.isHead(ctx)) {
			ctx.result(data);
		}
	}
	
	@NonNull
	private static String encodeSegment(@NonNull String name) {
		boolean dir = name.endsWith("/");
		String bare = dir ? name.substring(0, name.length() - 1) : name;
		return java.net.URLEncoder.encode(bare, StandardCharsets.UTF_8).replace("+", "%20") + (dir ? "/" : "");
	}
	
	@NonNull
	private static String readChecksum(@NonNull Context ctx) throws IOException {
		try (InputStream input = ctx.bodyInputStream()) {
			byte[] data = input.readNBytes(1024);
			String value = new String(data, StandardCharsets.UTF_8).strip();
			int space = value.indexOf(' ');
			return space < 0 ? value : value.substring(0, space);
		}
	}
	
	@Nullable
	protected static String checksumExtension(@NonNull String path) {
		int dot = path.lastIndexOf('.');
		if (dot < 0) {
			return null;
		}
		String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
		return CHECKSUM_EXTENSIONS.contains(extension) ? extension : null;
	}
	
	@NonNull
	protected static String stripExtension(@NonNull String path) {
		return path.substring(0, path.lastIndexOf('.'));
	}
	
	@NonNull
	protected static String fileName(@NonNull String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}
	
	@NonNull
	protected static String digest(@NonNull FileEntity file, @NonNull String algorithm) {
		return switch (algorithm) {
			case "sha1" -> file.sha1();
			case "md5" -> file.md5();
			case "sha256" -> file.sha256();
			case "sha512" -> file.sha512();
			default -> throw new IllegalArgumentException("Unknown checksum " + algorithm);
		};
	}
	
	@NonNull
	protected static String digestHex(@NonNull String algorithm, byte @NonNull [] data) {
		String name = switch (algorithm) {
			case "sha1" -> "SHA-1";
			case "md5" -> "MD5";
			case "sha256" -> "SHA-256";
			case "sha512" -> "SHA-512";
			default -> throw new IllegalArgumentException("Unknown checksum " + algorithm);
		};
		try {
			return HexFormat.of().formatHex(java.security.MessageDigest.getInstance(name).digest(data));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
	
	@NonNull
	protected static FileEntity fakeFile(@NonNull RepositoryEntity repository, @NonNull StoredBlob blob) {
		return new FileEntity(repository.name(), "", null, null, blob.size(), blob.sha256(), blob.sha1(), blob.md5(), blob.sha512(), "", null, java.time.Instant.now(), null);
	}
	
	@NonNull
	public static String contentType(@NonNull String path) {
		String name = fileName(path).toLowerCase(Locale.ROOT);
		if (checksumExtension(name) != null || name.endsWith(".asc") || name.endsWith(".txt")) {
			return "text/plain";
		}
		int dot = name.lastIndexOf('.');
		String extension = dot < 0 ? "" : name.substring(dot + 1);
		return switch (extension) {
			case "jar", "war", "ear", "aar" -> "application/java-archive";
			case "pom", "xml" -> "application/xml";
			case "module", "json" -> "application/json";
			case "zip" -> "application/zip";
			case "gz", "tgz" -> "application/gzip";
			case "html", "htm" -> "text/html";
			default -> "application/octet-stream";
		};
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = Requests.decodedSegments(rawPath);
		for (String segment : segments) {
			// An encoded slash would hide a ".." segment from this check
			if (".".equals(segment) || "..".equals(segment) || segment.contains("\\") || segment.contains("/")) {
				throw HttpError.badRequest("Invalid path");
			}
		}
		String path = String.join("/", segments);
		boolean directory = rawPath.isEmpty() || rawPath.endsWith("/");
		
		switch (Requests.method(ctx)) {
			case "GET", "HEAD" -> this.get(ctx, repository, path, directory);
			case "PUT", "POST" -> {
				if (path.isEmpty() || directory) {
					throw HttpError.badRequest("Can not upload to a directory");
				}
				this.put(ctx, repository, path);
			}
			case "DELETE" -> this.delete(ctx, repository, path);
			default -> throw HttpError.methodNotAllowed();
		}
	}
	
	protected void get(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path, boolean directory) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		RepositoryClass kind = this.services.repositories().classOf(repository.name());
		
		if (!directory && !path.isEmpty()) {
			if (kind == RepositoryClass.VIRTUAL) {
				List<RepositoryEntity> members = this.members(repository);
				if (this.serveMerged(ctx, repository, members, path)) {
					return;
				}
				for (RepositoryEntity member : members) {
					if (this.serveFrom(ctx, member, this.services.repositories().classOf(member.name()), path)) {
						return;
					}
				}
			} else if (this.serveFrom(ctx, repository, kind, path)) {
				return;
			}
		}
		
		if (!this.listDirectory(ctx, repository, kind, path)) {
			throw HttpError.notFound();
		}
	}
	
	/**
	 * Returns the members of a virtual repository in their resolution order, members of another type and virtual members are skipped,
	 * virtual repositories are never resolved recursively to avoid cycles.<br>
	 */
	@NonNull
	protected List<RepositoryEntity> members(@NonNull RepositoryEntity repository) throws SqlException {
		List<RepositoryEntity> members = new ArrayList<>();
		for (VirtualMemberEntity member : this.services.repositories().virtualMembers(repository.name())) {
			RepositoryEntity memberRepository = this.services.repositories().get(member.member());
			if (memberRepository != null && memberRepository.type().equals(repository.type()) && this.services.repositories().classOf(memberRepository.name()) != RepositoryClass.VIRTUAL) {
				members.add(memberRepository);
			}
		}
		return members;
	}
	
	/**
	 * Resolves a file of a local or remote repository: a stored file, generated content and checksum sidecars of a local repository,
	 * or the cached or upstream copy of a remote repository.<br>
	 *
	 * @return true if the request was served
	 */
	private boolean serveFrom(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull RepositoryClass kind, @NonNull String path) throws Exception {
		return switch (kind) {
			case LOCAL -> this.serveLocal(ctx, repository, path) || this.serveComputed(ctx, repository, path) || this.serveChecksum(ctx, repository, path);
			case REMOTE -> this.serveRemote(ctx, repository, path);
			case VIRTUAL -> false;
		};
	}
	
	private boolean serveLocal(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		FileEntity file = this.services.artifacts().get(repository.name(), path);
		if (file == null) {
			return false;
		}
		this.services.artifacts().serve(ctx, file);
		return true;
	}
	
	/**
	 * Answers a checksum sidecar ({@code file.sha1}) that was not uploaded with the digest of the stored file, if the format uses sidecars.<br>
	 */
	private boolean serveChecksum(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		String checksum = this.checksumSidecars() ? checksumExtension(path) : null;
		if (checksum == null) {
			return false;
		}
		FileEntity base = this.services.artifacts().get(repository.name(), stripExtension(path));
		if (base == null) {
			return false;
		}
		text(ctx, 200, digest(base, checksum));
		return true;
	}
	
	private boolean serveRemote(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		RemoteConfigEntity remote = this.services.repositories().remoteConfig(repository.name());
		if (remote == null || !remote.cacheEnabled()) {
			// Files cached while caching was enabled are still served
			if (this.serveLocal(ctx, repository, path)) {
				return true;
			}
			return remote != null && this.streamRemote(ctx, remote, path);
		}
		FileEntity file = this.cachedRemote(repository, remote, path);
		if (file == null) {
			return false;
		}
		this.services.artifacts().serve(ctx, file);
		return true;
	}
	
	/**
	 * Returns the cached copy of a file of a remote repository with caching enabled, fetching it from the upstream if it is not cached yet.<br>
	 * A {@linkplain #isVolatile volatile} path is fetched again once its copy is older than the configured time to live,
	 * the stale copy is kept and served if the upstream does not answer.<br>
	 *
	 * @return The cached file, null if it is neither cached nor available upstream
	 */
	@Nullable
	private FileEntity cachedRemote(@NonNull RepositoryEntity repository, @NonNull RemoteConfigEntity remote, @NonNull String path) throws Exception {
		FileEntity cached = this.services.artifacts().get(repository.name(), path);
		Duration ttl = Duration.ofMinutes(this.services.config().remoteMetadataTtlMinutes());
		if (cached != null && (!this.isVolatile(path) || cached.createdAt().plus(ttl).isAfter(Instant.now()))) {
			return cached;
		}
		ProxyService.Response response = this.services.proxy().open(remote, path);
		if (response == null) {
			return cached;
		}
		StoredBlob blob;
		try (response) {
			blob = this.services.artifacts().upload(response.body());
		} catch (IOException | HttpError e) {
			LOGGER.warn("Failed to cache {} from the upstream of {}: {}", path, repository.name(), e.getMessage());
			return cached;
		}
		String contentType = response.contentType() != null && !response.contentType().isBlank() ? response.contentType() : contentType(path);
		try {
			return this.services.artifacts().putCached(repository.name(), path, blob, contentType);
		} catch (SqlException e) {
			if (!SqlErrors.isUniqueViolation(e)) {
				throw e;
			}
			// A concurrent request for the same path cached it first, its copy is served
			FileEntity concurrent = this.services.artifacts().get(repository.name(), path);
			if (concurrent == null) {
				throw e;
			}
			return concurrent;
		}
	}
	
	/**
	 * Streams an upstream file to the client without storing it, the upstream response is closed once the response was written.<br>
	 */
	private boolean streamRemote(@NonNull Context ctx, @NonNull RemoteConfigEntity remote, @NonNull String path) {
		ProxyService.Response response = this.services.proxy().open(remote, path);
		if (response == null) {
			return false;
		}
		String contentType = response.contentType() != null && !response.contentType().isBlank() ? response.contentType() : contentType(path);
		ctx.status(200).contentType(contentType);
		ctx.header("X-Content-Type-Options", "nosniff");
		ctx.header("Content-Disposition", "attachment; filename=\"" + fileName(path).replaceAll("[^A-Za-z0-9._-]", "_") + "\"");
		if (response.contentLength() >= 0) {
			ctx.header("Content-Length", String.valueOf(response.contentLength()));
		}
		if (Requests.isHead(ctx)) {
			try {
				response.close();
			} catch (IOException ignored) {}
			return true;
		}
		ctx.result(response.body());
		return true;
	}
	
	/**
	 * Reads a small file of a member of a virtual repository, e.g. metadata that is merged across the members.<br>
	 * Only remote members are read here, generated content of a local member is produced by the format handler itself.<br>
	 *
	 * @return The content, null if the file does not exist or is larger than the limit
	 */
	protected byte @Nullable [] readRemote(@NonNull RepositoryEntity repository, @NonNull String path, int limit) throws Exception {
		RemoteConfigEntity remote = this.services.repositories().remoteConfig(repository.name());
		if (remote == null) {
			return null;
		}
		InputStream input;
		if (remote.cacheEnabled()) {
			FileEntity file = this.cachedRemote(repository, remote, path);
			if (file == null || file.size() > limit) {
				return null;
			}
			input = this.services.artifacts().blobStore().open(file.sha256());
		} else {
			ProxyService.Response response = this.services.proxy().open(remote, path);
			if (response == null) {
				return null;
			}
			input = response.body();
		}
		try (input) {
			byte[] data = input.readNBytes(limit + 1);
			return data.length > limit ? null : data;
		} catch (IOException e) {
			LOGGER.warn("Failed to read {} from the upstream of {}: {}", path, repository.name(), e.getMessage());
			return null;
		}
	}
	
	/**
	 * Serves files that are not stored but computed from the stored files of a local repository, like Maven metadata.<br>
	 *
	 * @return true if the request was handled
	 */
	protected boolean serveComputed(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		return false;
	}
	
	/**
	 * Serves files of a virtual repository that are merged from all members instead of taken from the first member, like Maven metadata.<br>
	 *
	 * @return true if the request was handled
	 */
	protected boolean serveMerged(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<RepositoryEntity> members, @NonNull String path) throws Exception {
		return false;
	}
	
	/**
	 * Whether the format uses checksum sidecars: a {@code file.sha1} is answered with the digest of {@code file}
	 * and an uploaded sidecar is verified against the stored file instead of being stored.<br>
	 */
	protected boolean checksumSidecars() {
		return false;
	}
	
	/**
	 * Whether the content at the path can change upstream, a remote repository fetches it again once its cached copy expired.<br>
	 */
	protected boolean isVolatile(@NonNull String path) {
		return false;
	}
	
	private boolean listDirectory(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull RepositoryClass kind, @NonNull String path) throws Exception {
		String prefix = path.isEmpty() ? "" : path + "/";
		ArtifactService.DirectoryListing listing;
		if (kind == RepositoryClass.VIRTUAL) {
			// Aggregate the (locally stored) content of all members, the first member wins on a path collision
			SortedSet<String> directories = new TreeSet<>();
			Map<String, FileEntity> byPath = new LinkedHashMap<>();
			for (RepositoryEntity member : this.members(repository)) {
				ArtifactService.DirectoryListing memberListing = this.services.artifacts().listDirectory(member.name(), prefix);
				directories.addAll(memberListing.directories());
				for (FileEntity file : memberListing.files()) {
					byPath.putIfAbsent(file.path(), file);
				}
			}
			listing = new ArtifactService.DirectoryListing(directories, new ArrayList<>(byPath.values()));
		} else {
			listing = this.services.artifacts().listDirectory(repository.name(), prefix);
		}
		if (listing.isEmpty() && !path.isEmpty()) {
			return false;
		}
		// The links of the listing are relative, they only resolve below the directory if its url ends with a slash
		String rawPath = Requests.rawPath(ctx);
		if (!rawPath.endsWith("/")) {
			String query = ctx.queryString();
			ctx.redirect(rawPath.substring(rawPath.lastIndexOf('/') + 1) + "/" + (query == null ? "" : "?" + query), HttpStatus.MOVED_PERMANENTLY);
			return true;
		}
		
		Map<String, FileEntity> entries = new TreeMap<>();
		listing.directories().forEach(directory -> entries.put(directory, null));
		for (FileEntity file : listing.files()) {
			entries.put(file.path().substring(prefix.length()), file);
		}
		
		StringBuilder html = new StringBuilder();
		String title = "/" + this.type().id() + "/" + repository.name() + "/" + prefix;
		html.append("<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\"><title>").append(Requests.escapeHtml(title)).append("</title></head>\n<body>\n");
		html.append("<h1>").append(Requests.escapeHtml(title)).append("</h1>\n<pre>\n");
		if (!path.isEmpty()) {
			html.append("<a href=\"../\">../</a>\n");
		}
		for (Map.Entry<String, FileEntity> entry : entries.entrySet()) {
			String name = entry.getKey();
			html.append("<a href=\"").append(Requests.escapeHtml(encodeSegment(name))).append("\">").append(Requests.escapeHtml(name)).append("</a>");
			FileEntity file = entry.getValue();
			if (file != null) {
				html.append(" ".repeat(Math.max(1, 60 - name.length()))).append(LISTING_TIME.format(file.createdAt())).append("  ").append(file.size());
			}
			html.append("\n");
		}
		html.append("</pre>\n</body>\n</html>\n");
		Requests.html(ctx, 200, html.toString());
		return true;
	}
	
	protected void put(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		if (this.services.repositories().classOf(repository.name()) != RepositoryClass.LOCAL) {
			throw HttpError.badRequest("Can not publish to a remote or virtual repository");
		}
		
		if (this.consumeComputedUpload(ctx, repository, path)) {
			return;
		}
		
		String checksum = this.checksumSidecars() ? checksumExtension(path) : null;
		if (checksum != null) {
			FileEntity base = this.services.artifacts().get(repository.name(), stripExtension(path));
			if (base != null) {
				String declared = readChecksum(ctx);
				if (!declared.equalsIgnoreCase(digest(base, checksum))) {
					throw HttpError.badRequest("Declared digest does not match uploaded content");
				}
				ctx.status(201);
				return;
			}
		}
		
		StoredBlob blob;
		try (InputStream input = ctx.bodyInputStream()) {
			blob = this.services.artifacts().upload(input);
		}
		this.verifyDeclaredChecksums(ctx, repository, blob);
		
		boolean immutable = !repository.allowRedeploy() && !this.isMutable(path);
		try {
			this.store(ctx, repository, path, blob, username(principal), immutable);
		} catch (HttpError | SqlException e) {
			this.services.artifacts().releaseBlobs(List.of(fakeFile(repository, blob)));
			// A concurrent upload to the same path committed first
			if (immutable && SqlErrors.isUniqueViolation(e)) {
				throw HttpError.conflict(this.conflictMessage(path));
			}
			throw e;
		}
		ctx.status(201);
	}
	
	/**
	 * Checks an immutable path inside the transaction of a store, so concurrent uploads of different content can not both succeed.<br>
	 *
	 * @return true if the identical file is already stored and nothing has to be written
	 * @throws HttpError 409 if a file with different content is stored at the path
	 */
	protected boolean alreadyStored(@NonNull UnitOfWork work, @NonNull RepositoryEntity repository, @NonNull String path, @NonNull StoredBlob blob, boolean immutable) throws SqlException {
		if (!immutable) {
			return false;
		}
		FileEntity existing = this.services.artifacts().get(work, repository.name(), path);
		if (existing == null) {
			return false;
		}
		if (existing.sha256().equals(blob.sha256())) {
			return true;
		}
		throw HttpError.conflict(this.conflictMessage(path));
	}
	
	/**
	 * Checks the {@code X-Checksum-*} headers which are sent by some clients (e.g. JFrog CLI).<br>
	 */
	private void verifyDeclaredChecksums(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull StoredBlob blob) throws Exception {
		Map<String, String> headers = Map.of("X-Checksum-Sha1", "sha1", "X-Checksum-Sha256", "sha256", "X-Checksum-Md5", "md5");
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			String declared = ctx.header(entry.getKey());
			if (declared != null && !declared.isBlank() && !declared.strip().equalsIgnoreCase(blob.digest(entry.getValue()))) {
				this.services.artifacts().releaseBlobs(List.of(fakeFile(repository, blob)));
				throw HttpError.badRequest("Declared digest does not match uploaded content");
			}
		}
	}
	
	/**
	 * Records the uploaded file, an immutable path is checked with {@link #alreadyStored} in the same transaction.<br>
	 */
	protected void store(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path, @NonNull StoredBlob blob, @Nullable String username, boolean immutable) throws Exception {
		this.services.artifacts().transaction(work -> {
			if (!this.alreadyStored(work, repository, path, blob, immutable)) {
				this.services.artifacts().put(work, repository.name(), path, blob, contentType(path), null, null, null, username);
			}
			return null;
		});
	}
	
	protected boolean consumeComputedUpload(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		return false;
	}
	
	protected boolean isMutable(@NonNull String path) {
		return false;
	}
	
	@NonNull
	protected String conflictMessage(@NonNull String path) {
		return "File " + path + " already exists; redeploy is not allowed";
	}
	
	protected void delete(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		this.authorize(ctx, repository, AccessLevel.DELETE);
		if (this.services.repositories().classOf(repository.name()) != RepositoryClass.LOCAL) {
			throw HttpError.badRequest("Can not delete from a remote or virtual repository");
		}
		if (path.isEmpty()) {
			throw HttpError.badRequest("Refusing to delete the repository root, delete the repository instead");
		}
		
		this.services.artifacts().transaction(work -> {
			List<FileEntity> deleted = new ArrayList<>();
			FileEntity file = this.services.artifacts().get(work, repository.name(), path);
			if (file != null) {
				this.services.artifacts().delete(work, repository.name(), path);
				deleted.add(file);
			} else {
				deleted.addAll(this.services.artifacts().deletePrefix(work, repository.name(), path + "/"));
			}
			if (deleted.isEmpty()) {
				throw HttpError.notFound();
			}
			this.afterDelete(work, repository, deleted);
			return null;
		});
		ctx.status(204);
	}
	
	/**
	 * Called inside the transaction of a delete, after the files were deleted.<br>
	 */
	protected void afterDelete(@NonNull UnitOfWork work, @NonNull RepositoryEntity repository, @NonNull List<FileEntity> deleted) throws SqlException {}
}
