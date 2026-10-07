package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.artifactory.util.Json;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.*;

import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

import static net.luis.artifactory.http.Requests.escapeHtml;

/**
 * PyPI repository implementing the simple repository API (PEP 503 / PEP 691) and the legacy upload API used by twine.<br>
 * <ul>
 *     <li>Index url for pip: {@code {base}/pypi/{repo}/simple/}</li>
 *     <li>Upload url for twine: {@code {base}/pypi/{repo}/}</li>
 * </ul>
 */
public class PypiHandler extends FormatHandler {
	
	private static final String JSON_TYPE = "application/vnd.pypi.simple.v1+json";
	private static final Pattern NORMALIZE = Pattern.compile("[-_.]+");
	private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+!-]*");
	private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.!+_-]{0,127}");
	
	public PypiHandler(@NonNull Services services) {
		super(services);
	}
	
	@Override
	public @NonNull RepositoryType type() {
		return RepositoryType.PYPI;
	}
	
	public static @NonNull String normalize(@NonNull String name) {
		return NORMALIZE.matcher(name).replaceAll("-").toLowerCase(Locale.ROOT);
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = Requests.decodedSegments(rawPath);
		boolean trailingSlash = rawPath.endsWith("/");
		String method = Requests.method(ctx);
		
		if ("POST".equals(method) && (segments.isEmpty() || segments.equals(List.of("legacy")))) {
			this.upload(ctx, repository);
			return;
		}
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			throw HttpError.methodNotAllowed();
		}
		this.authorize(ctx, repository, AccessLevel.READ);
		
		if (segments.isEmpty()) {
			ctx.redirect(this.repositoryUrl(ctx, repository) + "/simple/");
		} else if (segments.getFirst().equals("simple") && segments.size() == 1) {
			if (!trailingSlash) {
				ctx.redirect(this.repositoryUrl(ctx, repository) + "/simple/");
				return;
			}
			this.projectList(ctx, repository);
		} else if (segments.getFirst().equals("simple") && segments.size() == 2) {
			String name = segments.get(1);
			String normalized = normalize(name);
			if (!trailingSlash || !normalized.equals(name)) {
				ctx.redirect(this.repositoryUrl(ctx, repository) + "/simple/" + normalized + "/", io.javalin.http.HttpStatus.MOVED_PERMANENTLY);
				return;
			}
			this.projectPage(ctx, repository, normalized);
		} else if (segments.getFirst().equals("packages") && segments.size() == 2) {
			FileEntity file = this.services.artifacts().get(repository.name(), "packages/" + segments.get(1));
			if (file == null) {
				throw HttpError.notFound();
			}
			this.services.artifacts().serve(ctx, file, "application/octet-stream");
		} else if (segments.getFirst().equals("pypi") && segments.size() == 3 && segments.get(2).equals("json")) {
			this.projectJson(ctx, repository, normalize(segments.get(1)));
		} else {
			throw HttpError.notFound();
		}
	}
	
	private static boolean wantsJson(@NonNull Context ctx) {
		String format = ctx.queryParam("format");
		if (format != null) {
			return format.contains("json");
		}
		return Requests.accepts(ctx, JSON_TYPE);
	}
	
	//region Simple API
	private void projectList(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		List<String> projects = this.services.packages().packageNames(repository.name());
		if (wantsJson(ctx)) {
			ObjectNode root = Json.object();
			root.putObject("meta").put("api-version", "1.1");
			ArrayNode array = root.putArray("projects");
			for (String project : projects) {
				array.addObject().put("name", project);
			}
			ctx.status(200).contentType(JSON_TYPE).result(Json.write(root));
			return;
		}
		
		StringBuilder html = new StringBuilder("<!DOCTYPE html>\n<html>\n  <head>\n    <meta name=\"pypi:repository-version\" content=\"1.1\">\n    <title>Simple index</title>\n  </head>\n  <body>\n");
		for (String project : projects) {
			html.append("    <a href=\"").append(escapeHtml(project)).append("/\">").append(escapeHtml(project)).append("</a>\n");
		}
		html.append("  </body>\n</html>\n");
		Requests.html(ctx, 200, html.toString());
	}

	private void projectPage(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String normalized) throws Exception {
		List<FileEntity> files = this.services.artifacts().listByPackage(repository.name(), normalized);
		if (files.isEmpty()) {
			throw HttpError.notFound();
		}
		Map<String, PackageVersionEntity> versions = new HashMap<>();
		for (PackageVersionEntity version : this.services.packages().versions(repository.name(), normalized)) {
			versions.put(version.version(), version);
		}
		
		if (wantsJson(ctx)) {
			ObjectNode root = Json.object();
			root.putObject("meta").put("api-version", "1.1");
			root.put("name", normalized);
			ArrayNode versionArray = root.putArray("versions");
			new TreeSet<>(versions.keySet()).forEach(versionArray::add);
			ArrayNode array = root.putArray("files");
			for (FileEntity file : files) {
				ObjectNode metadata = Json.parseObject(file.metadata());
				ObjectNode entry = array.addObject();
				String fileName = fileName(file);
				entry.put("filename", fileName);
				entry.put("url", "../../packages/" + fileName);
				entry.putObject("hashes").put("sha256", file.sha256());
				String requiresPython = Json.text(metadata, "requires_python");
				if (requiresPython != null && !requiresPython.isBlank()) {
					entry.put("requires-python", requiresPython);
				}
				entry.put("size", file.size());
				entry.put("upload-time", DateTimeFormatter.ISO_INSTANT.format(file.createdAt()));
				PackageVersionEntity version = file.version() == null ? null : versions.get(file.version());
				entry.put("yanked", version != null && version.yanked());
			}
			ctx.status(200).contentType(JSON_TYPE).result(Json.write(root));
			return;
		}
		
		StringBuilder html = new StringBuilder("<!DOCTYPE html>\n<html>\n  <head>\n    <meta name=\"pypi:repository-version\" content=\"1.1\">\n    <title>Links for ")
			.append(escapeHtml(normalized)).append("</title>\n  </head>\n  <body>\n    <h1>Links for ").append(escapeHtml(normalized)).append("</h1>\n");
		for (FileEntity file : files) {
			ObjectNode metadata = Json.parseObject(file.metadata());
			String fileName = fileName(file);
			html.append("    <a href=\"../../packages/").append(escapeHtml(fileName)).append("#sha256=").append(file.sha256()).append("\"");
			String requiresPython = Json.text(metadata, "requires_python");
			if (requiresPython != null && !requiresPython.isBlank()) {
				html.append(" data-requires-python=\"").append(escapeHtml(requiresPython)).append("\"");
			}
			PackageVersionEntity version = file.version() == null ? null : versions.get(file.version());
			if (version != null && version.yanked()) {
				html.append(" data-yanked=\"\"");
			}
			html.append(">").append(escapeHtml(fileName)).append("</a><br/>\n");
		}
		html.append("  </body>\n</html>\n");
		Requests.html(ctx, 200, html.toString());
	}

	private void projectJson(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String normalized) throws Exception {
		List<PackageVersionEntity> versions = this.services.packages().versions(repository.name(), normalized);
		if (versions.isEmpty()) {
			throw HttpError.notFound();
		}
		List<FileEntity> files = this.services.artifacts().listByPackage(repository.name(), normalized);
		PackageVersionEntity latest = versions.getLast();
		ObjectNode latestMetadata = Json.parseObject(latest.metadata());
		
		ObjectNode root = Json.object();
		ObjectNode info = root.putObject("info");
		info.put("name", Json.text(latestMetadata, "name", normalized));
		info.put("version", latest.version());
		info.put("summary", Json.text(latestMetadata, "summary", ""));
		ObjectNode releases = root.putObject("releases");
		for (PackageVersionEntity version : versions) {
			ArrayNode array = releases.putArray(version.version());
			for (FileEntity file : files) {
				if (version.version().equals(file.version())) {
					ObjectNode entry = array.addObject();
					entry.put("filename", fileName(file));
					entry.put("url", this.repositoryUrl(ctx, repository) + "/" + file.path());
					entry.putObject("digests").put("sha256", file.sha256()).put("md5", file.md5());
					entry.put("size", file.size());
					entry.put("yanked", version.yanked());
					entry.put("upload_time_iso_8601", DateTimeFormatter.ISO_INSTANT.format(file.createdAt()));
				}
			}
		}
		json(ctx, 200, root);
	}
	
	private static @NonNull String fileName(@NonNull FileEntity file) {
		return file.path().substring(file.path().lastIndexOf('/') + 1);
	}
	//endregion
	
	//region Upload
	private void upload(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		if (!ctx.isMultipartFormData()) {
			throw HttpError.badRequest("Expected multipart/form-data upload");
		}
		
		String action = ctx.formParam(":action");
		if (action != null && !"file_upload".equals(action)) {
			throw HttpError.badRequest("Unsupported action: " + action);
		}
		String name = required(ctx, "name");
		String version = required(ctx, "version");
		if (!VERSION.matcher(version).matches()) {
			throw HttpError.badRequest("Invalid version: " + version);
		}
		UploadedFile content = ctx.uploadedFile("content");
		if (content == null) {
			throw HttpError.badRequest("Missing file content");
		}
		String fileName = content.filename();
		if (fileName.contains("/") || fileName.contains("\\") || !FILE_NAME.matcher(fileName).matches()) {
			throw HttpError.badRequest("Invalid file name: " + fileName);
		}
		
		String normalized = normalize(name);
		String path = "packages/" + fileName;
		FileEntity existing = this.services.artifacts().get(repository.name(), path);
		if (existing != null && !repository.allowRedeploy()) {
			throw HttpError.conflict("File already exists: " + fileName);
		}
		
		StoredBlob blob;
		try (InputStream input = content.content()) {
			blob = this.services.artifacts().upload(input);
		}
		String sha256 = ctx.formParam("sha256_digest");
		String md5 = ctx.formParam("md5_digest");
		if ((sha256 != null && !sha256.isBlank() && !sha256.strip().equalsIgnoreCase(blob.sha256())) || (md5 != null && !md5.isBlank() && !md5.strip().equalsIgnoreCase(blob.md5()))) {
			this.services.artifacts().releaseBlobs(List.of(PathRepositoryHandler.fakeFile(repository, blob)));
			throw HttpError.badRequest("sha256_digest does not match uploaded content");
		}
		
		ObjectNode fileMetadata = Json.object();
		putIfPresent(ctx, fileMetadata, "requires_python");
		putIfPresent(ctx, fileMetadata, "filetype");
		putIfPresent(ctx, fileMetadata, "pyversion");
		putIfPresent(ctx, fileMetadata, "metadata_version");
		this.services.artifacts().put(repository.name(), path, blob, "application/octet-stream", normalized, version, Json.write(fileMetadata), username(principal));
		
		ObjectNode versionMetadata = Json.object();
		versionMetadata.put("name", name);
		putIfPresent(ctx, versionMetadata, "summary");
		putIfPresent(ctx, versionMetadata, "author");
		putIfPresent(ctx, versionMetadata, "license");
		putIfPresent(ctx, versionMetadata, "home_page");
		this.services.packages().upsert(repository.name(), normalized, version, Json.write(versionMetadata), username(principal));
		text(ctx, 200, "OK");
	}
	
	private static @NonNull String required(@NonNull Context ctx, @NonNull String field) {
		String value = ctx.formParam(field);
		if (value == null || value.isBlank()) {
			throw HttpError.badRequest("Missing field: " + field);
		}
		return value.strip();
	}
	
	private static void putIfPresent(@NonNull Context ctx, @NonNull ObjectNode node, @NonNull String field) {
		String value = ctx.formParam(field);
		if (value != null && !value.isBlank()) {
			node.put(field, value.strip());
		}
	}
	//endregion
}
