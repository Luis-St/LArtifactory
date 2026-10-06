package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.entity.FileEntity;
import net.luis.artifactory.database.entity.RepositoryEntity;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.storage.StoredBlob;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Base handler of path based repositories (Maven and generic) supporting GET, HEAD, PUT and DELETE of arbitrary paths.<br>
 */
public abstract class PathRepositoryHandler extends FormatHandler {
	
	protected static final List<String> CHECKSUM_EXTENSIONS = List.of("sha1", "md5", "sha256", "sha512");
	private static final DateTimeFormatter LISTING_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
	
	protected PathRepositoryHandler(@NonNull Services services) {
		super(services);
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = Requests.decodedSegments(rawPath);
		for (String segment : segments) {
			if (".".equals(segment) || "..".equals(segment) || segment.contains("\\")) {
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
	
	//region GET
	protected void get(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path, boolean directory) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		
		if (!directory && !path.isEmpty()) {
			FileEntity file = this.services.artifacts().get(repository.name(), path);
			if (file != null) {
				this.services.artifacts().serve(ctx, file);
				return;
			}
			if (this.serveVirtual(ctx, repository, path)) {
				return;
			}
			String checksum = checksumExtension(path);
			if (checksum != null) {
				FileEntity base = this.services.artifacts().get(repository.name(), stripExtension(path));
				if (base != null) {
					text(ctx, 200, digest(base, checksum));
					return;
				}
			}
		}
		
		if (!this.listDirectory(ctx, repository, path)) {
			throw HttpError.notFound();
		}
	}
	
	/**
	 * Serves files that are not stored but generated, like Maven metadata.<br>
	 *
	 * @return true if the request was handled
	 */
	protected boolean serveVirtual(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		return false;
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
	
	private boolean listDirectory(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		String prefix = path.isEmpty() ? "" : path + "/";
		List<FileEntity> files = this.services.artifacts().list(repository.name(), prefix);
		if (files.isEmpty() && !path.isEmpty()) {
			return false;
		}
		
		Map<String, FileEntity> entries = new TreeMap<>();
		for (FileEntity file : files) {
			String rest = file.path().substring(prefix.length());
			int slash = rest.indexOf('/');
			if (slash >= 0) {
				entries.putIfAbsent(rest.substring(0, slash + 1), null);
			} else {
				entries.put(rest, file);
			}
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
		ctx.status(200).html(html.toString());
		return true;
	}
	
	private static @NonNull String encodeSegment(@NonNull String name) {
		boolean dir = name.endsWith("/");
		String bare = dir ? name.substring(0, name.length() - 1) : name;
		return java.net.URLEncoder.encode(bare, StandardCharsets.UTF_8).replace("+", "%20") + (dir ? "/" : "");
	}
	//endregion
	
	//region PUT
	protected void put(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		
		if (this.consumeVirtualUpload(ctx, repository, path)) {
			return;
		}
		
		String checksum = checksumExtension(path);
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
		
		FileEntity existing = this.services.artifacts().get(repository.name(), path);
		StoredBlob blob;
		try (InputStream input = ctx.bodyInputStream()) {
			blob = this.services.artifacts().upload(input);
		}
		
		if (existing != null && !repository.allowRedeploy() && !this.isMutable(path)) {
			if (existing.sha256().equals(blob.sha256())) {
				ctx.status(201);
				return;
			}
			this.services.artifacts().releaseBlobs(List.of(fakeFile(repository, blob)));
			throw HttpError.conflict(this.conflictMessage(path));
		}
		
		this.verifyDeclaredChecksums(ctx, repository, blob);
		this.store(ctx, repository, path, blob, username(principal));
		ctx.status(201);
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
	
	protected void store(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path, @NonNull StoredBlob blob, @Nullable String username) throws Exception {
		this.services.artifacts().put(repository.name(), path, blob, contentType(path), null, null, null, username);
	}
	
	protected boolean consumeVirtualUpload(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		return false;
	}
	
	protected boolean isMutable(@NonNull String path) {
		return false;
	}
	
	protected @NonNull String conflictMessage(@NonNull String path) {
		return "File " + path + " already exists; redeploy is not allowed";
	}
	
	private static @NonNull String readChecksum(@NonNull Context ctx) throws IOException {
		try (InputStream input = ctx.bodyInputStream()) {
			byte[] data = input.readNBytes(1024);
			String value = new String(data, StandardCharsets.UTF_8).strip();
			int space = value.indexOf(' ');
			return space < 0 ? value : value.substring(0, space);
		}
	}
	//endregion
	
	//region DELETE
	protected void delete(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		this.authorize(ctx, repository, AccessLevel.DELETE);
		if (path.isEmpty()) {
			throw HttpError.badRequest("Refusing to delete the repository root, delete the repository instead");
		}
		
		List<FileEntity> deleted = new ArrayList<>();
		FileEntity file = this.services.artifacts().get(repository.name(), path);
		if (file != null) {
			this.services.artifacts().delete(repository.name(), path);
			deleted.add(file);
		} else {
			List<FileEntity> files = this.services.artifacts().list(repository.name(), path + "/");
			this.services.artifacts().deletePrefix(repository.name(), path + "/");
			deleted.addAll(files);
		}
		if (deleted.isEmpty()) {
			throw HttpError.notFound();
		}
		this.afterDelete(repository, deleted);
		ctx.status(204);
	}
	
	protected void afterDelete(@NonNull RepositoryEntity repository, @NonNull List<FileEntity> deleted) throws Exception {}
	//endregion
	
	//region Helpers
	protected static @Nullable String checksumExtension(@NonNull String path) {
		int dot = path.lastIndexOf('.');
		if (dot < 0) {
			return null;
		}
		String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
		return CHECKSUM_EXTENSIONS.contains(extension) ? extension : null;
	}
	
	protected static @NonNull String stripExtension(@NonNull String path) {
		return path.substring(0, path.lastIndexOf('.'));
	}
	
	protected static @NonNull String fileName(@NonNull String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}
	
	protected static @NonNull String digest(@NonNull FileEntity file, @NonNull String algorithm) {
		return switch (algorithm) {
			case "sha1" -> file.sha1();
			case "md5" -> file.md5();
			case "sha256" -> file.sha256();
			case "sha512" -> file.sha512();
			default -> throw new IllegalArgumentException("Unknown checksum " + algorithm);
		};
	}
	
	protected static @NonNull String digestHex(@NonNull String algorithm, byte @NonNull [] data) {
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
	
	protected static @NonNull FileEntity fakeFile(@NonNull RepositoryEntity repository, @NonNull StoredBlob blob) {
		return new FileEntity(repository.name(), "", null, null, blob.size(), blob.sha256(), blob.sha1(), blob.md5(), blob.sha512(), "", null, java.time.Instant.now(), null);
	}
	
	public static @NonNull String contentType(@NonNull String path) {
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
	//endregion
}
