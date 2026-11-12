package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.*;
import net.luis.artifactory.database.SqlErrors;
import net.luis.artifactory.database.UnitOfWork;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.artifactory.storage.BlobStore;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.artifactory.util.Json;
import net.luis.artifactory.util.Versions;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * npm registry implementing the CouchDB style registry API used by npm, yarn and pnpm.<br>
 * Registry url: {@code {base}/npm/{repo}/}<br>
 */
public class NpmHandler extends FormatHandler {
	
	private static final Pattern NAME = Pattern.compile("(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9._~][a-z0-9._~-]*");
	private static final Pattern TAG = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");
	/**
	 * A strict semantic version, a version such as {@code latest} or {@code 1.0} would shadow a dist tag or become an invalid tarball name.<br>
	 */
	private static final Pattern SEMVER = Pattern.compile(
		"(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
			"(?:-(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*)?" +
			"(?:\\+[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?"
	);
	private static final int MAX_SEARCH_SIZE = 250;
	private static final int SEARCH_BATCH_SIZE = 100;
	
	public NpmHandler(@NonNull Services services) {
		super(services);
	}
	
	/**
	 * Joins {@code @scope} and {@code name} segments of unencoded scoped package names.<br>
	 */
	@NonNull
	private static List<String> joinScopes(@NonNull List<String> segments) {
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
	
	@NonNull
	private static String tarballName(@NonNull String name, @NonNull String version) {
		String bare = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
		return bare + "-" + version + ".tgz";
	}
	
	@NonNull
	private static String revision(@NonNull List<PackageVersionEntity> versions, @NonNull Map<String, String> tags) {
		StringBuilder builder = new StringBuilder();
		for (PackageVersionEntity version : versions) {
			builder.append(version.version()).append(version.metadata().hashCode()).append(';');
		}
		builder.append(tags);
		return versions.size() + "-" + BlobStore.sha256Hex(builder.toString()).substring(0, 16);
	}
	
	/**
	 * Returns the tarball of a version, a single attachment with another name is only taken if a single version is published.<br>
	 */
	private static byte @NonNull [] findAttachment(@NonNull JsonNode attachments, @NonNull String name, @NonNull String version, boolean singleVersion) {
		JsonNode attachment = attachments.get(name + "-" + version + ".tgz");
		if (attachment == null) {
			attachment = attachments.get(tarballName(name, version));
		}
		if (attachment == null && singleVersion && attachments.size() == 1) {
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
	
	@NonNull
	private static JsonNode parseBody(byte @NonNull [] body) {
		try {
			return Json.parse(body);
		} catch (JacksonException e) {
			throw npmError(400, "Invalid json body");
		}
	}
	
	@NonNull
	private static ObjectNode ok() {
		ObjectNode node = Json.object();
		node.put("ok", true);
		return node;
	}
	
	@NonNull
	private static HttpError npmError(int status, @NonNull String message) {
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
	
	@NonNull
	private static String hex(@NonNull String algorithm, byte @NonNull [] data) {
		return HexFormat.of().formatHex(digest(algorithm, data));
	}
	
	@NonNull
	private static JsonNode latestManifest(@NonNull List<PackageVersionEntity> versions, @NonNull Map<String, String> tags) {
		String latest = tags.get("latest");
		for (PackageVersionEntity version : versions) {
			if (version.version().equals(latest)) {
				return Json.parseObject(version.metadata());
			}
		}
		return Json.parseObject(versions.getLast().metadata());
	}
	
	@Override
	@NonNull
	public RepositoryType type() {
		return RepositoryType.NPM;
	}
	
	@Override
	@NonNull
	protected String challenge(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
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
		if ("-".equals(segments.getFirst())) {
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
		} else if (rest.size() == 2 && "-rev".equals(rest.getFirst())) {
			switch (method) {
				case "PUT" -> this.publishOrUpdate(ctx, repository, name, true);
				case "DELETE" -> this.unpublishAll(ctx, repository, name);
				default -> throw HttpError.methodNotAllowed();
			}
		} else if (rest.size() == 2 && "-".equals(rest.getFirst())) {
			if (!"GET".equals(method) && !"HEAD".equals(method)) {
				throw HttpError.methodNotAllowed();
			}
			this.authorize(ctx, repository, AccessLevel.READ);
			FileEntity file = this.services.artifacts().get(repository.name(), name + "/-/" + rest.get(1));
			if (file == null) {
				throw npmError(404, "Not found");
			}
			this.services.artifacts().serve(ctx, file, "application/octet-stream");
		} else if (rest.size() == 4 && "-".equals(rest.getFirst()) && "-rev".equals(rest.get(2)) && "DELETE".equals(method)) {
			this.authorize(ctx, repository, AccessLevel.DELETE);
			this.services.artifacts().delete(repository.name(), name + "/-/" + rest.get(1));
			json(ctx, 200, ok());
		} else if (rest.size() == 1 && ("GET".equals(method) || "HEAD".equals(method))) {
			this.versionManifest(ctx, repository, name, rest.getFirst());
		} else {
			throw npmError(404, "Not found");
		}
	}
	
	private void special(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<String> segments, @NonNull String method) throws Exception {
		String first = segments.isEmpty() ? "" : segments.getFirst();
		switch (first) {
			case "ping" -> json(ctx, 200, Json.object());
			case "whoami" -> {
				Principal principal = this.services.auth().requireAuthenticated(ctx, this.challenge(ctx, repository));
				ObjectNode root = Json.object();
				root.put("username", principal.username());
				json(ctx, 200, root);
			}
			case "user" -> this.user(ctx, repository, segments.subList(1, segments.size()), method);
			case "v1" -> {
				if (segments.size() == 2 && "search".equals(segments.get(1))) {
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
		if (segments.size() == 2 && "token".equals(segments.get(0)) && "DELETE".equals(method)) {
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
		
		// Parsed before authentication, so only a small body is accepted
		JsonNode body = parseBody(this.services.bodies().json(ctx));
		String name = Json.text(body, "name", username);
		String password = Json.text(body, "password");
		UserEntity user = password == null ? null : this.services.auth().login(ctx, name, password);
		if (user == null) {
			throw npmError(401, "Invalid username or password");
		}
		int days = this.services.config().npmTokenDays();
		Instant expiresAt = days == 0 ? null : Instant.now().plus(days, ChronoUnit.DAYS);
		UserService.CreatedToken token = this.services.users().createToken(user.username(), "npm login", AccessLevel.DELETE, expiresAt);
		ObjectNode root = ok();
		root.put("id", "org.couchdb.user:" + user.username());
		root.put("token", token.secret());
		json(ctx, 201, root);
	}
	
	private void search(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		String text = Objects.requireNonNullElse(ctx.queryParam("text"), "").toLowerCase(Locale.ROOT).strip();
		int size = Math.min(parseInt(ctx.queryParam("size"), 20), MAX_SEARCH_SIZE);
		int from = parseInt(ctx.queryParam("from"), 0);
		
		// Versions and tags are loaded for a batch of packages at a time, only the requested page is kept in memory
		List<SearchHit> page = new ArrayList<>();
		int total = 0;
		List<String> names = this.services.packages().packageNames(repository.name());
		for (int start = 0; start < names.size(); start += SEARCH_BATCH_SIZE) {
			List<String> batch = names.subList(start, Math.min(names.size(), start + SEARCH_BATCH_SIZE));
			Map<String, List<PackageVersionEntity>> versionsByName = this.services.packages().versions(repository.name(), batch);
			Map<String, Map<String, String>> tagsByName = this.services.packages().tags(repository.name(), batch);
			for (String packageName : batch) {
				List<PackageVersionEntity> versions = versionsByName.getOrDefault(packageName, List.of());
				if (versions.isEmpty()) {
					continue;
				}
				boolean onPage = total >= from && page.size() < size;
				boolean nameMatches = text.isEmpty() || packageName.contains(text);
				// The manifest is only parsed when it is searched or shown
				JsonNode manifest = nameMatches && !onPage ? null : latestManifest(versions, tagsByName.getOrDefault(packageName, Map.of()));
				if (nameMatches || manifest.toString().toLowerCase(Locale.ROOT).contains(text)) {
					if (onPage) {
						page.add(new SearchHit(packageName, versions, manifest));
					}
					total++;
				}
			}
		}
		
		ObjectNode root = Json.object();
		ArrayNode objects = root.putArray("objects");
		for (SearchHit hit : page) {
			JsonNode manifest = hit.manifest();
			ObjectNode object = objects.addObject();
			ObjectNode pkg = object.putObject("package");
			pkg.put("name", hit.name());
			pkg.put("version", Json.text(manifest, "version", ""));
			pkg.put("description", Json.text(manifest, "description", ""));
			pkg.put("date", DateTimeFormatter.ISO_INSTANT.format(hit.versions().getLast().createdAt()));
			if (manifest.has("keywords")) {
				pkg.set("keywords", manifest.get("keywords"));
			}
			pkg.putObject("links");
			String publisher = Objects.requireNonNullElse(hit.versions().getLast().createdBy(), "unknown");
			pkg.putObject("publisher").put("username", publisher).put("email", "");
			pkg.putArray("maintainers").addObject().put("username", publisher).put("email", "");
			ObjectNode score = object.putObject("score");
			score.put("final", 1.0);
			score.putObject("detail").put("quality", 1.0).put("popularity", 1.0).put("maintenance", 1.0);
			object.put("searchScore", 1.0);
		}
		root.put("total", total);
		root.put("time", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
		json(ctx, 200, root);
	}
	
	private void distTags(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<String> segments, @NonNull String method) throws Exception {
		if (segments.size() < 2 || !"dist-tags".equals(segments.get(1)) || segments.size() > 3) {
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
				JsonNode body = parseBody(this.services.bodies().json(ctx));
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
	
	@NonNull
	private List<PackageVersionEntity> requireVersions(@NonNull RepositoryEntity repository, @NonNull String name) throws Exception {
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
	
	@NonNull
	private ObjectNode manifest(@NonNull PackageVersionEntity version, @NonNull String tarballBase) {
		ObjectNode manifest = Json.parseObject(version.metadata());
		ObjectNode dist = manifest.has("dist") && manifest.get("dist").isObject() ? (ObjectNode) manifest.get("dist") : manifest.putObject("dist");
		dist.put("tarball", tarballBase + tarballName(version.packageName(), version.version()));
		return manifest;
	}
	
	private void publishOrUpdate(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name, boolean revision) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		this.services.bodies().withPackageBody(ctx, data -> {
			JsonNode body = parseBody(data);
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
			return null;
		});
	}
	
	private void publish(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name, @NonNull ObjectNode body, @Nullable Principal principal) throws Exception {
		JsonNode versionsNode = body.get("versions");
		if (versionsNode == null || !versionsNode.isObject() || versionsNode.isEmpty()) {
			throw npmError(400, "No versions to publish");
		}
		
		// Store the tarballs first, then record all versions and tags in one transaction so a failed or concurrent publish
		// leaves no partially published package behind
		Map<String, ObjectNode> manifests = new LinkedHashMap<>();
		Map<String, StoredBlob> blobs = new HashMap<>();
		for (Map.Entry<String, JsonNode> entry : versionsNode.properties()) {
			String version = entry.getKey();
			if (!(entry.getValue() instanceof ObjectNode manifest)) {
				throw npmError(400, "Invalid version manifest for " + version);
			}
			if (!SEMVER.matcher(version).matches()) {
				throw npmError(400, "Invalid version: " + version + ", versions must be valid semver");
			}
			String manifestName = Json.text(manifest, "name", name);
			String manifestVersion = Json.text(manifest, "version", version);
			if (!manifestName.equals(name) || !manifestVersion.equals(version)) {
				throw npmError(400, "Manifest " + manifestName + "@" + manifestVersion + " does not match " + name + "@" + version);
			}
			manifest.put("name", name);
			manifest.put("version", version);
			// The public registry answers a publish over an existing version with 403, npm retries a 409 as a revision conflict
			if (this.services.packages().get(repository.name(), name, version) != null) {
				throw npmError(403, "cannot modify pre-existing version: " + version);
			}
			
			byte[] tarball = findAttachment(body.get("_attachments"), name, version, versionsNode.size() == 1);
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
			
			blobs.put(version, this.services.artifacts().upload(tarball));
			manifests.put(version, manifest);
		}
		
		JsonNode distTags = body.get("dist-tags");
		try {
			this.services.artifacts().transaction(work -> {
				for (Map.Entry<String, ObjectNode> entry : manifests.entrySet()) {
					String version = entry.getKey();
					if (this.services.packages().get(work, repository.name(), name, version) != null) {
						throw npmError(403, "cannot modify pre-existing version: " + version);
					}
					this.services.artifacts().put(work, repository.name(), name + "/-/" + tarballName(name, version), blobs.get(version), "application/octet-stream", name, version, null, username(principal));
					this.services.packages().create(work, repository.name(), name, version, Json.write(entry.getValue()), username(principal));
				}
				
				Map<String, String> tags = this.services.packages().tags(work, repository.name(), name);
				Set<String> known = new HashSet<>(manifests.keySet());
				this.services.packages().versions(work, repository.name(), name).forEach(version -> known.add(version.version()));
				if (distTags != null && distTags.isObject()) {
					for (Map.Entry<String, JsonNode> entry : distTags.properties()) {
						// A tag can only point to a version that exists, otherwise clients resolving it would fail
						if (entry.getValue().isString() && known.contains(entry.getValue().asString()) && TAG.matcher(entry.getKey()).matches()) {
							this.services.packages().setTag(work, repository.name(), name, entry.getKey(), entry.getValue().asString());
							tags.put(entry.getKey(), entry.getValue().asString());
						}
					}
				}
				if (!tags.containsKey("latest")) {
					this.services.packages().setTag(work, repository.name(), name, "latest", manifests.keySet().stream().max(Versions.SEMVER).orElseThrow());
				}
				return null;
			});
		} catch (RuntimeException | SqlException e) {
			this.releaseUploads(repository, blobs.values());
			if (SqlErrors.isUniqueViolation(e)) {
				throw npmError(403, "cannot modify pre-existing version, it was published concurrently");
			}
			throw e;
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
			if (versionsNode.get(version.version()) == null && revision) {
				removed.add(version);
			}
		}
		if (!removed.isEmpty()) {
			this.authorize(ctx, repository, AccessLevel.DELETE);
		}
		
		JsonNode distTags = body.get("dist-tags");
		this.services.artifacts().transaction(work -> {
			this.applyUpdate(work, repository, name, versions, versionsNode, distTags, removed, revision);
			return null;
		});
		
		ObjectNode response = ok();
		response.put("id", name);
		json(ctx, 201, response);
	}
	
	private void applyUpdate(@NonNull UnitOfWork work, @NonNull RepositoryEntity repository, @NonNull String name, @NonNull List<PackageVersionEntity> versions, @NonNull JsonNode versionsNode, @Nullable JsonNode distTags, @NonNull List<PackageVersionEntity> removed, boolean revision) throws SqlException {
		for (PackageVersionEntity version : versions) {
			JsonNode updated = versionsNode.get(version.version());
			if (updated == null) {
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
				this.services.packages().updateMetadata(work, repository.name(), name, version.version(), Json.write(manifest));
			}
		}
		
		for (PackageVersionEntity version : removed) {
			this.services.packages().delete(work, repository.name(), name, version.version());
			this.services.artifacts().deleteVersion(work, repository.name(), name, version.version());
		}
		
		Set<String> remaining = new HashSet<>();
		for (PackageVersionEntity version : this.services.packages().versions(work, repository.name(), name)) {
			remaining.add(version.version());
		}
		Map<String, String> tags = this.services.packages().tags(work, repository.name(), name);
		if (revision && distTags != null && distTags.isObject()) {
			for (String tag : tags.keySet()) {
				if (!distTags.has(tag)) {
					this.services.packages().deleteTag(work, repository.name(), name, tag);
				}
			}
		}
		if (distTags != null && distTags.isObject()) {
			for (Map.Entry<String, JsonNode> entry : distTags.properties()) {
				String version = entry.getValue().asString();
				if (remaining.contains(version) && TAG.matcher(entry.getKey()).matches()) {
					this.services.packages().setTag(work, repository.name(), name, entry.getKey(), version);
				}
			}
		}
		for (Map.Entry<String, String> tag : this.services.packages().tags(work, repository.name(), name).entrySet()) {
			if (!remaining.contains(tag.getValue())) {
				this.services.packages().deleteTag(work, repository.name(), name, tag.getKey());
			}
		}
		if (!remaining.isEmpty() && !this.services.packages().tags(work, repository.name(), name).containsKey("latest")) {
			this.services.packages().setTag(work, repository.name(), name, "latest", remaining.stream().max(Versions.SEMVER).orElseThrow());
		}
	}
	
	private void unpublishAll(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String name) throws Exception {
		this.authorize(ctx, repository, AccessLevel.DELETE);
		List<PackageVersionEntity> versions = this.requireVersions(repository, name);
		this.services.artifacts().transaction(work -> {
			for (PackageVersionEntity version : versions) {
				this.services.packages().delete(work, repository.name(), name, version.version());
				this.services.artifacts().deleteVersion(work, repository.name(), name, version.version());
			}
			this.services.packages().deleteTags(work, repository.name(), name);
			return null;
		});
		json(ctx, 200, ok());
	}
	
	private void releaseUploads(@NonNull RepositoryEntity repository, @NonNull Collection<StoredBlob> blobs) throws SqlException {
		this.services.artifacts().releaseBlobs(blobs.stream().map(blob -> PathRepositoryHandler.fakeFile(repository, blob)).toList());
	}
	
	private record SearchHit(@NonNull String name, @NonNull List<PackageVersionEntity> versions, @NonNull JsonNode manifest) {}
}
