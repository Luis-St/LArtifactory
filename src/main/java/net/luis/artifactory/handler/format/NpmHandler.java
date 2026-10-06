package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.*;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.artifactory.storage.BlobStore;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.artifactory.util.Json;
import net.luis.artifactory.util.Versions;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * npm registry implementing the CouchDB style registry API used by npm, yarn and pnpm.<br>
 * Registry url: {@code {base}/npm/{repo}/}<br>
 */
public class NpmHandler extends FormatHandler {
	
	private static final Pattern NAME = Pattern.compile("(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9._~][a-z0-9._~-]*");
	private static final Pattern TAG = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");
	
	public NpmHandler(@NonNull Services services) {
		super(services);
	}
	
	@Override
	public @NonNull RepositoryType type() {
		return RepositoryType.NPM;
	}
	
	@Override
	protected @NonNull String challenge(@NonNull RepositoryEntity repository) {
		return "Bearer realm=\"" + repository.name() + "\"";
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = joinScopes(Requests.decodedSegments(rawPath));
		String method = Requests.method(ctx);
		
		if (segments.isEmpty()) {
			this.authorize(ctx, repository, AccessLevel.READ);
			ObjectNode root = Json.object();
			root.put("db_name", repository.name());
			json(ctx, 200, root);
			return;
		}
		if (segments.getFirst().equals("-")) {
			this.special(ctx, repository, segments.subList(1, segments.size()), method);
			return;
		}
		
		String name = segments.getFirst();
		if (!NAME.matcher(name).matches()) {
			throw npmError(400, "Invalid package name: " + name);
		}
		List<String> rest = segments.subList(1, segments.size());
		
		if (rest.isEmpty()) {
			switch (method) {
				case "GET", "HEAD" -> this.packument(ctx, repository, name);
				case "PUT" -> this.publishOrUpdate(ctx, repository, name, false);
				default -> throw HttpError.methodNotAllowed();
			}
		} else if (rest.size() == 2 && rest.getFirst().equals("-rev")) {
			switch (method) {
				case "PUT" -> this.publishOrUpdate(ctx, repository, name, true);
				case "DELETE" -> this.unpublishAll(ctx, repository, name);
				default -> throw HttpError.methodNotAllowed();
			}
		} else if (rest.size() == 2 && rest.getFirst().equals("-")) {
			if (!"GET".equals(method) && !"HEAD".equals(method)) {
				throw HttpError.methodNotAllowed();
			}
			this.authorize(ctx, repository, AccessLevel.READ);
			FileEntity file = this.services.artifacts().get(repository.name(), name + "/-/" + rest.get(1));
			if (file == null) {
				throw npmError(404, "Not found");
			}
			this.services.artifacts().serve(ctx, file, "application/octet-stream");
		} else if (rest.size() == 4 && rest.getFirst().equals("-") && rest.get(2).equals("-rev") && "DELETE".equals(method)) {
			this.authorize(ctx, repository, AccessLevel.DELETE);
			this.services.artifacts().delete(repository.name(), name + "/-/" + rest.get(1));
			json(ctx, 200, ok());
		} else if (rest.size() == 1 && ("GET".equals(method) || "HEAD".equals(method))) {
			this.versionManifest(ctx, repository, name, rest.getFirst());
		} else {
			throw npmError(404, "Not found");
		}
	}
	
	/**
	 * Joins {@code @scope} and {@code name} segments of unencoded scoped package names.<br>
	 */
	private static @NonNull List<String> joinScopes(@NonNull List<String> segments) {
		List<String> result = new ArrayList<>();
		for (int i = 0; i < segments.size(); i++) {
			String segment = segments.get(i);
			if (segment.startsWith("@") && !segment.contains("/") && i + 1 < segments.size()) {
				result.add(segment + "/" + segments.get(++i));
			} else {
				result.add(segment);
			}
		}
		return result;
	}
	
	//region Special endpoints
	private void special(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<String> segments, @NonNull String method) throws Exception {
		String first = segments.isEmpty() ? "" : segments.getFirst();
		switch (first) {
			case "ping" -> json(ctx, 200, Json.object());
			case "whoami" -> {
				Principal principal = this.services.auth().requireAuthenticated(ctx, this.challenge(repository));
				ObjectNode root = Json.object();
				root.put("username", principal.username());
				json(ctx, 200, root);
			}
			case "user" -> this.user(ctx, repository, segments.subList(1, segments.size()), method);
			case "v1" -> {
				if (segments.size() == 2 && segments.get(1).equals("search")) {
					this.search(ctx, repository);
				} else {
					throw npmError(404, "Not found"); // e.g. /-/v1/login, npm falls back to the legacy login
				}
			}
			case "package" -> this.distTags(ctx, repository, segments.subList(1, segments.size()), method);
			case "npm" -> json(ctx, 200, Json.object()); // audit endpoints, no advisories available
			default -> throw npmError(404, "Not found");
		}
	}
	
	private void user(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<String> segments, @NonNull String method) throws Exception {
		if (segments.size() == 2 && segments.get(0).equals("token") && "DELETE".equals(method)) {
			this.services.users().deleteTokenBySecret(segments.get(1));
			this.services.auth().invalidateCache();
			json(ctx, 200, ok());
			return;
		}
		if (segments.size() != 1 || !segments.getFirst().startsWith("org.couchdb.user:")) {
			throw npmError(404, "Not found");
		}
		String username = segments.getFirst().substring("org.couchdb.user:".length());
		if ("GET".equals(method)) {
			ObjectNode root = Json.object();
			root.put("_id", "org.couchdb.user:" + username);
			root.put("name", username);
			json(ctx, 200, root);
			return;
		}
		if (!"PUT".equals(method)) {
			throw HttpError.methodNotAllowed();
		}
		
		JsonNode body = parseBody(this.readBody(ctx));
		String name = Json.text(body, "name", username);
		String password = Json.text(body, "password");
		UserEntity user = this.services.users().get(name);
		if (password == null || user == null || !PasswordHasher.verify(password, user.passwordHash())) {
			throw npmError(401, "Invalid username or password");
		}
		UserService.CreatedToken token = this.services.users().createToken(user.username(), "npm login", AccessLevel.DELETE, null);
		ObjectNode root = ok();
		root.put("id", "org.couchdb.user:" + user.username());
		root.put("token", token.secret());
		json(ctx, 201, root);
	}
	
	private void search(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		String text = Objects.requireNonNullElse(ctx.queryParam("text"), "").toLowerCase(Locale.ROOT).strip();
		int size = parseInt(ctx.queryParam("size"), 20);
		int from = parseInt(ctx.queryParam("from"), 0);
		
		List<Map.Entry<String, List<PackageVersionEntity>>> matches = new ArrayList<>();
		for (Map.Entry<String, List<PackageVersionEntity>> entry : this.services.packages().grouped(repository.name()).entrySet()) {
			if (text.isEmpty() || entry.getKey().contains(text) || this.latestManifest(repository, entry.getKey(), entry.getValue()).toString().toLowerCase(Locale.ROOT).contains(text)) {
				matches.add(entry);
			}
		}
		
		ObjectNode root = Json.object();
		ArrayNode objects = root.putArray("objects");
		for (Map.Entry<String, List<PackageVersionEntity>> entry : matches.stream().skip(from).limit(size).toList()) {
			JsonNode manifest = this.latestManifest(repository, entry.getKey(), entry.getValue());
			ObjectNode object = objects.addObject();
			ObjectNode pkg = object.putObject("package");
			pkg.put("name", entry.getKey());
			pkg.put("version", Json.text(manifest, "version", ""));
			pkg.put("description", Json.text(manifest, "description", ""));
			pkg.put("date", DateTimeFormatter.ISO_INSTANT.format(entry.getValue().getLast().createdAt()));
			if (manifest.has("keywords")) {
				pkg.set("keywords", manifest.get("keywords"));
			}
			pkg.putObject("links");
			ObjectNode score = object.putObject("score");
			score.put("final", 1.0);
			score.putObject("detail").put("quality", 1.0).put("popularity", 1.0).put("maintenance", 1.0);
			object.put("searchScore", 1.0);
		}
		root.put("total", matches.size());
		root.put("time", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
		json(ctx, 200, root);
	}
	
	private @NonNull JsonNode latestManifest(@NonNull RepositoryEntity repository, @NonNull String name, @NonNull List<PackageVersionEntity> versions) throws Exception {
		String latest = this.services.packages().tags(repository.name(), name).get("latest");
		for (PackageVersionEntity version : versions) {
			if (version.version().equals(latest)) {
				return Json.parseObject(version.metadata());
			}
		}
		return Json.parseObject(versions.getLast().metadata());
	}
	
	private void distTags(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<String> segments, @NonNull String method) throws Exception {
		if (segments.size() < 2 || !segments.get(1).equals("dist-tags") || segments.size() > 3) {
			throw npmError(404, "Not found");
		}
		String name = segments.getFirst();
		if (segments.size() == 2) {
			if (!"GET".equals(method)) {
				throw HttpError.methodNotAllowed();
			}
			this.authorize(ctx, repository, AccessLevel.READ);
			this.requireVersions(repository, name);
			json(ctx, 200, this.services.packages().tags(repository.name(), name));
			return;
		}
		
		String tag = segments.get(2);
		this.authorize(ctx, repository, AccessLevel.WRITE);
		this.requireVersions(repository, name);
		switch (method) {
			case "PUT", "POST" -> {
				if (!TAG.matcher(tag).matches()) {
					throw npmError(400, "Invalid tag name: " + tag);
				}
				JsonNode body = parseBody(this.readBody(ctx));
				String version = body.isString() ? body.asString() : null;
				if (version == null || this.services.packages().get(repository.name(), name, version) == null) {
					throw npmError(404, "Version not found: " + version);
				}
				this.services.packages().setTag(repository.name(), name, tag, version);
			}
			case "DELETE" -> {
				if ("latest".equals(tag)) {
					throw npmError(400, "Can not delete the latest tag");
				}
				this.services.packages().deleteTag(repository.name(), name, tag);
			}
			default -> throw HttpError.methodNotAllowed();
		}
		json(ctx, 200, this.services.packages().tags(repository.name(), name));
	}
	//endregion
	
	//region Read
	private @NonNull List<PackageVersionEntity> requireVersions(@NonNull RepositoryEntity repository, @NonNull String name) throws Exception {
		List<PackageVersionEntity> versions = this.services.packages().versions(repository.name(), name);
		if (versions.isEmpty()) {
			throw npmError(404, "Not found");
		}
		return versions;
	}
	
	private void packument(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		List<PackageVersionEntity> versions = this.requireVersions(repository, name);
		Map<String, String> tags = this.services.packages().tags(repository.name(), name);
		String tarballBase = this.repositoryUrl(ctx, repository) + "/" + name + "/-/";
		
		ObjectNode root = Json.object();
		root.put("_id", name);
		root.put("_rev", revision(versions, tags));
		root.put("name", name);
		ObjectNode distTags = root.putObject("dist-tags");
		tags.forEach(distTags::put);
		
		ObjectNode versionsNode = root.putObject("versions");
		ObjectNode time = Json.object();
		time.put("created", DateTimeFormatter.ISO_INSTANT.format(versions.getFirst().createdAt()));
		time.put("modified", DateTimeFormatter.ISO_INSTANT.format(versions.getLast().createdAt()));
		ObjectNode latest = null;
		for (PackageVersionEntity version : versions) {
			ObjectNode manifest = this.manifest(version, tarballBase);
			versionsNode.set(version.version(), manifest);
			time.put(version.version(), DateTimeFormatter.ISO_INSTANT.format(version.createdAt()));
			if (version.version().equals(tags.get("latest"))) {
				latest = manifest;
			}
		}
		if (latest == null) {
			latest = (ObjectNode) versionsNode.get(versions.getLast().version());
		}
		root.set("time", time);
		for (String field : List.of("description", "readme", "license", "homepage", "repository", "keywords", "author", "bugs", "maintainers")) {
			if (latest.has(field)) {
				root.set(field, latest.get(field));
			}
		}
		json(ctx, 200, root);
	}
	
	private void versionManifest(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name, @NonNull String versionOrTag) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		String version = this.services.packages().tags(repository.name(), name).getOrDefault(versionOrTag, versionOrTag);
		PackageVersionEntity entity = this.services.packages().get(repository.name(), name, version);
		if (entity == null) {
			throw npmError(404, "Not found");
		}
		json(ctx, 200, this.manifest(entity, this.repositoryUrl(ctx, repository) + "/" + name + "/-/"));
	}
	
	private @NonNull ObjectNode manifest(@NonNull PackageVersionEntity version, @NonNull String tarballBase) {
		ObjectNode manifest = Json.parseObject(version.metadata());
		ObjectNode dist = manifest.has("dist") && manifest.get("dist").isObject() ? (ObjectNode) manifest.get("dist") : manifest.putObject("dist");
		dist.put("tarball", tarballBase + tarballName(version.packageName(), version.version()));
		return manifest;
	}
	
	private static @NonNull String tarballName(@NonNull String name, @NonNull String version) {
		String bare = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
		return bare + "-" + version + ".tgz";
	}
	
	private static @NonNull String revision(@NonNull List<PackageVersionEntity> versions, @NonNull Map<String, String> tags) {
		StringBuilder builder = new StringBuilder();
		for (PackageVersionEntity version : versions) {
			builder.append(version.version()).append(version.metadata().hashCode()).append(';');
		}
		builder.append(tags);
		return versions.size() + "-" + BlobStore.sha256Hex(builder.toString()).substring(0, 16);
	}
	//endregion
	
	//region Write
	private void publishOrUpdate(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name, boolean revision) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		JsonNode body = parseBody(this.readBody(ctx));
		if (!body.isObject()) {
			throw npmError(400, "Invalid package document");
		}
		String bodyName = Json.text(body, "name", name);
		if (!bodyName.equals(name)) {
			throw npmError(400, "Package name in body (" + bodyName + ") does not match url (" + name + ")");
		}
		
		JsonNode attachments = body.get("_attachments");
		if (attachments != null && attachments.isObject() && !attachments.isEmpty()) {
			this.publish(ctx, repository, name, (ObjectNode) body, principal);
		} else {
			this.update(ctx, repository, name, (ObjectNode) body, revision);
		}
	}
	
	private void publish(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name, @NonNull ObjectNode body, @Nullable Principal principal) throws Exception {
		JsonNode versionsNode = body.get("versions");
		if (versionsNode == null || !versionsNode.isObject() || versionsNode.isEmpty()) {
			throw npmError(400, "No versions to publish");
		}
		
		List<String> published = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : versionsNode.properties()) {
			String version = entry.getKey();
			if (!(entry.getValue() instanceof ObjectNode manifest)) {
				throw npmError(400, "Invalid version manifest for " + version);
			}
			if (this.services.packages().get(repository.name(), name, version) != null) {
				throw npmError(409, "cannot modify pre-existing version: " + version);
			}
			
			byte[] tarball = findAttachment(body.get("_attachments"), name, version);
			ObjectNode dist = manifest.has("dist") && manifest.get("dist").isObject() ? (ObjectNode) manifest.get("dist") : manifest.putObject("dist");
			String shasum = hex("SHA-1", tarball);
			String integrity = "sha512-" + Base64.getEncoder().encodeToString(digest("SHA-512", tarball));
			String declaredShasum = Json.text(dist, "shasum");
			String declaredIntegrity = Json.text(dist, "integrity");
			if ((declaredShasum != null && !declaredShasum.equalsIgnoreCase(shasum)) || (declaredIntegrity != null && declaredIntegrity.startsWith("sha512-") && !declaredIntegrity.equals(integrity))) {
				throw npmError(400, "Declared digest does not match uploaded content");
			}
			dist.put("shasum", shasum);
			dist.put("integrity", integrity);
			dist.remove("tarball");
			if (!manifest.has("readme") && body.has("readme")) {
				manifest.set("readme", body.get("readme"));
			}
			manifest.remove("_id");
			manifest.put("_id", name + "@" + version);
			
			StoredBlob blob = this.services.artifacts().upload(tarball);
			this.services.artifacts().put(repository.name(), name + "/-/" + tarballName(name, version), blob, "application/octet-stream", name, version, null, username(principal));
			this.services.packages().create(repository.name(), name, version, Json.write(manifest), username(principal));
			published.add(version);
		}
		
		Map<String, String> tags = this.services.packages().tags(repository.name(), name);
		JsonNode distTags = body.get("dist-tags");
		if (distTags != null && distTags.isObject()) {
			for (Map.Entry<String, JsonNode> entry : distTags.properties()) {
				if (entry.getValue().isString() && TAG.matcher(entry.getKey()).matches()) {
					this.services.packages().setTag(repository.name(), name, entry.getKey(), entry.getValue().asString());
					tags.put(entry.getKey(), entry.getValue().asString());
				}
			}
		}
		if (!tags.containsKey("latest")) {
			this.services.packages().setTag(repository.name(), name, "latest", published.stream().max(Versions.SEMVER).orElseThrow());
		}
		
		List<PackageVersionEntity> versions = this.services.packages().versions(repository.name(), name);
		ObjectNode response = ok();
		response.put("id", name);
		response.put("rev", revision(versions, this.services.packages().tags(repository.name(), name)));
		json(ctx, 201, response);
	}
	
	/**
	 * Updates the metadata of existing versions (deprecation) and, for revision updates, removes versions missing in the body (unpublish).<br>
	 */
	private void update(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name, @NonNull ObjectNode body, boolean revision) throws Exception {
		List<PackageVersionEntity> versions = this.requireVersions(repository, name);
		JsonNode versionsNode = body.get("versions");
		if (versionsNode == null || !versionsNode.isObject()) {
			throw npmError(400, "Missing versions");
		}
		
		List<PackageVersionEntity> removed = new ArrayList<>();
		for (PackageVersionEntity version : versions) {
			JsonNode updated = versionsNode.get(version.version());
			if (updated == null) {
				if (revision) {
					removed.add(version);
				}
				continue;
			}
			ObjectNode manifest = Json.parseObject(version.metadata());
			JsonNode deprecated = updated.get("deprecated");
			boolean changed;
			if (deprecated != null && deprecated.isString() && !deprecated.asString().isEmpty()) {
				changed = !deprecated.asString().equals(Json.text(manifest, "deprecated"));
				manifest.put("deprecated", deprecated.asString());
			} else {
				changed = manifest.remove("deprecated") != null;
			}
			if (changed) {
				this.services.packages().updateMetadata(repository.name(), name, version.version(), Json.write(manifest));
			}
		}
		
		if (!removed.isEmpty()) {
			this.authorize(ctx, repository, AccessLevel.DELETE);
			for (PackageVersionEntity version : removed) {
				this.services.packages().delete(repository.name(), name, version.version());
				this.services.artifacts().deleteVersion(repository.name(), name, version.version());
			}
		}
		
		Set<String> remaining = new HashSet<>();
		for (PackageVersionEntity version : this.services.packages().versions(repository.name(), name)) {
			remaining.add(version.version());
		}
		JsonNode distTags = body.get("dist-tags");
		Map<String, String> tags = this.services.packages().tags(repository.name(), name);
		if (revision && distTags != null && distTags.isObject()) {
			for (String tag : tags.keySet()) {
				if (!distTags.has(tag)) {
					this.services.packages().deleteTag(repository.name(), name, tag);
				}
			}
		}
		if (distTags != null && distTags.isObject()) {
			for (Map.Entry<String, JsonNode> entry : distTags.properties()) {
				String version = entry.getValue().asString();
				if (remaining.contains(version) && TAG.matcher(entry.getKey()).matches()) {
					this.services.packages().setTag(repository.name(), name, entry.getKey(), version);
				}
			}
		}
		for (Map.Entry<String, String> tag : this.services.packages().tags(repository.name(), name).entrySet()) {
			if (!remaining.contains(tag.getValue())) {
				this.services.packages().deleteTag(repository.name(), name, tag.getKey());
			}
		}
		if (!remaining.isEmpty() && !this.services.packages().tags(repository.name(), name).containsKey("latest")) {
			this.services.packages().setTag(repository.name(), name, "latest", remaining.stream().max(Versions.SEMVER).orElseThrow());
		}
		
		ObjectNode response = ok();
		response.put("id", name);
		json(ctx, 201, response);
	}
	
	private void unpublishAll(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name) throws Exception {
		this.authorize(ctx, repository, AccessLevel.DELETE);
		for (PackageVersionEntity version : this.requireVersions(repository, name)) {
			this.services.packages().delete(repository.name(), name, version.version());
			this.services.artifacts().deleteVersion(repository.name(), name, version.version());
		}
		this.services.packages().deleteTags(repository.name(), name);
		json(ctx, 200, ok());
	}
	
	private static byte @NonNull [] findAttachment(@NonNull JsonNode attachments, @NonNull String name, @NonNull String version) {
		JsonNode attachment = attachments.get(name + "-" + version + ".tgz");
		if (attachment == null) {
			attachment = attachments.get(tarballName(name, version));
		}
		if (attachment == null && attachments.size() == 1) {
			attachment = attachments.values().iterator().next();
		}
		if (attachment == null) {
			throw npmError(400, "Missing tarball attachment for version " + version);
		}
		String data = Json.text(attachment, "data");
		if (data == null) {
			throw npmError(400, "Missing tarball data for version " + version);
		}
		byte[] tarball;
		try {
			tarball = Base64.getDecoder().decode(data);
		} catch (IllegalArgumentException e) {
			throw npmError(400, "Invalid base64 tarball data");
		}
		JsonNode length = attachment.get("length");
		if (length != null && length.isNumber() && length.asLong() != tarball.length) {
			throw npmError(400, "Attachment length does not match uploaded content");
		}
		return tarball;
	}
	//endregion
	
	//region Helpers
	private static @NonNull JsonNode parseBody(byte @NonNull [] body) {
		try {
			return Json.parse(body);
		} catch (JacksonException e) {
			throw npmError(400, "Invalid json body");
		}
	}
	
	private static @NonNull ObjectNode ok() {
		ObjectNode node = Json.object();
		node.put("ok", true);
		return node;
	}
	
	private static @NonNull HttpError npmError(int status, @NonNull String message) {
		ObjectNode node = Json.object();
		node.put("error", message);
		return HttpError.json(status, message, Json.write(node));
	}
	
	private static int parseInt(@Nullable String value, int defaultValue) {
		try {
			return value == null ? defaultValue : Math.max(0, Integer.parseInt(value));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
	
	private static byte @NonNull [] digest(@NonNull String algorithm, byte @NonNull [] data) {
		try {
			return MessageDigest.getInstance(algorithm).digest(data);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
	
	private static @NonNull String hex(@NonNull String algorithm, byte @NonNull [] data) {
		return HexFormat.of().formatHex(digest(algorithm, data));
	}
	//endregion
}
