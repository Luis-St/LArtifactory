package net.luis.artifactory.handler.format;

import com.google.common.util.concurrent.Striped;
import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.SqlErrors;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.artifactory.util.Json;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.regex.Pattern;

/**
 * OCI distribution (Docker registry v2) api for container images and other OCI artifacts.<br>
 * <p>
 *     Docker only talks to {@code /v2/} at the root of a host, so the api is served at {@code /v2/{repository}/{image}/...}
 *     (the first component of the image name selects the repository), at {@code /v2/{image}/...} on a host mapped to the
 *     repository and at {@code /oci/{repository}/v2/{image}/...} for clients that support a path prefix.
 * </p>
 * <p>
 *     Storage layout in a repository:
 * </p>
 * <ul>
 *     <li>{@code blobs/sha256/{hex}}: layers and configs, shared by all images of the repository</li>
 *     <li>{@code {image}/manifests/sha256/{hex}}: manifests and indexes, recorded as version {@code sha256:{hex}} of the package {@code {image}}</li>
 *     <li>tags are dist tags of the package pointing to a manifest digest</li>
 * </ul>
 */
public class OciHandler extends FormatHandler {
	
	private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:(?:\\.|_|__|-+)[a-z0-9]+)*(?:/[a-z0-9]+(?:(?:\\.|_|__|-+)[a-z0-9]+)*)*");
	private static final Pattern TAG = Pattern.compile("[a-zA-Z0-9_][a-zA-Z0-9._-]{0,127}");
	private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
	private static final int MAX_NAME_LENGTH = 255;
	/**
	 * Registries should accept manifests of at least 4 MiB according to the distribution spec.<br>
	 */
	private static final long MAX_MANIFEST_SIZE = 4 * 1024 * 1024;
	private static final int MAX_UPLOAD_SESSIONS = 1024;
	private static final int DEFAULT_PAGE_SIZE = 1000;
	/**
	 * Matches the age at which the blob store deletes the temporary file of an upload.<br>
	 */
	private static final Duration UPLOAD_MAX_AGE = Duration.ofHours(24);
	private static final String BLOB_CONTENT_TYPE = "application/octet-stream";
	/**
	 * Lifetime of a session created by the token endpoint for a password, clients fetch a new token once it expired.<br>
	 */
	private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);
	/**
	 * Clients are told the token expires earlier than its session, so a request in flight never carries an expired session.<br>
	 */
	private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofMinutes(5);
	private static final String TOKEN_SERVICE = "LArtifactory";
	private static final String TOKEN_ATTRIBUTE = "artifactory.ociToken";
	/**
	 * Serializes the reference check and deletion of blobs (garbage collection and blob deletes) against manifest pushes of the same repository,
	 * otherwise a manifest could be committed for a blob that is deleted at the same time.<br>
	 */
	private static final Striped<ReadWriteLock> REFERENCE_LOCKS = Striped.readWriteLock(64);
	/**
	 * Set by the dispatcher for requests to the root {@code /v2/{repository}/...} api, the client side name then starts with the repository.<br>
	 */
	public static final String ROOT_ATTRIBUTE = "artifactory.ociRoot";
	public static final String API_VERSION_HEADER = "Docker-Distribution-API-Version";
	public static final String API_VERSION = "registry/2.0";
	public static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
	public static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
	public static final String DOCKER_MANIFEST = "application/vnd.docker.distribution.manifest.v2+json";
	public static final String DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json";
	private static final Set<String> INDEX_TYPES = Set.of(OCI_INDEX, DOCKER_MANIFEST_LIST);
	private static final Set<String> MANIFEST_TYPES = Set.of(OCI_MANIFEST, OCI_INDEX, DOCKER_MANIFEST, DOCKER_MANIFEST_LIST);
	private final Map<String, UploadSession> uploads = new ConcurrentHashMap<>();
	
	public OciHandler(@NonNull Services services) {
		super(services);
	}
	
	@NonNull
	public static String blobPath(@NonNull String digest) {
		return "blobs/sha256/" + digest.substring("sha256:".length());
	}
	
	@NonNull
	public static String manifestPath(@NonNull String image, @NonNull String digest) {
		return image + "/manifests/sha256/" + digest.substring("sha256:".length());
	}
	
	public static boolean isValidName(@NonNull String name) {
		return name.length() <= MAX_NAME_LENGTH && NAME.matcher(name).matches();
	}
	
	/**
	 * Returns the digests of the blobs a manifest references (config and layers, foreign layers with urls excluded).<br>
	 */
	@NonNull
	public static List<String> referencedBlobs(@NonNull JsonNode manifest) {
		List<String> digests = new ArrayList<>();
		String config = Json.text(manifest.path("config"), "digest");
		if (config != null) {
			digests.add(config);
		}
		for (JsonNode layer : manifest.path("layers")) {
			String digest = Json.text(layer, "digest");
			if (digest != null && !layer.has("urls")) {
				digests.add(digest);
			}
		}
		return digests;
	}
	
	@NonNull
	static HttpError error(int status, @NonNull String code, @NonNull String message) {
		ObjectNode root = Json.object();
		root.putArray("errors").addObject().put("code", code).put("message", message);
		return HttpError.json(status, message, Json.write(root));
	}
	
	@NonNull
	private static String sha256(byte @NonNull [] data) {
		try {
			return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Missing digest algorithm SHA-256", e);
		}
	}
	
	@NonNull
	private static String range(long size) {
		return "0-" + Math.max(0, size - 1);
	}
	
	private static int pageSize(@NonNull Context ctx) {
		try {
			return Math.clamp(Integer.parseInt(Objects.requireNonNullElse(ctx.queryParam("n"), String.valueOf(DEFAULT_PAGE_SIZE))), 0, DEFAULT_PAGE_SIZE);
		} catch (NumberFormatException e) {
			return DEFAULT_PAGE_SIZE;
		}
	}
	
	/**
	 * Returns the page of the sorted values after {@code last}, a {@code Link} header to the next page is added if there are more values.<br>
	 */
	@NonNull
	private static List<String> page(@NonNull Context ctx, @NonNull SortedSet<String> values, @NonNull String url) {
		String last = ctx.queryParam("last");
		int size = pageSize(ctx);
		SortedSet<String> remaining = last == null ? values : values.tailSet(last + "\0");
		List<String> page = remaining.stream().limit(size).toList();
		if (remaining.size() > page.size() && !page.isEmpty()) {
			ctx.header("Link", "<" + url + "?n=" + size + "&last=" + java.net.URLEncoder.encode(page.getLast(), java.nio.charset.StandardCharsets.UTF_8) + ">; rel=\"next\"");
		}
		return page;
	}
	
	@NonNull
	private static String mediaType(@Nullable String header, @NonNull JsonNode manifest) {
		String fromBody = Json.text(manifest, "mediaType");
		String fromHeader = header == null ? null : header.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
		if (fromHeader != null && !MANIFEST_TYPES.contains(fromHeader)) {
			fromHeader = null;
		}
		if (fromHeader != null && fromBody != null && !fromHeader.equals(fromBody)) {
			throw error(400, "MANIFEST_INVALID", "Content-Type " + fromHeader + " does not match the mediaType " + fromBody + " of the manifest");
		}
		String mediaType = fromHeader != null ? fromHeader : fromBody;
		if (mediaType == null) {
			// The mediaType field is optional in an OCI manifest, an index is recognized by its manifests list
			mediaType = manifest.has("manifests") ? OCI_INDEX : OCI_MANIFEST;
		}
		if (!MANIFEST_TYPES.contains(mediaType)) {
			throw error(400, "MANIFEST_INVALID", "Unsupported manifest media type: " + mediaType);
		}
		return mediaType;
	}
	
	/**
	 * Deletes the blobs of the OCI repositories that no manifest references and that are older than the given age.<br>
	 * The age protects blobs of a push in progress, its manifest is uploaded after all blobs.<br>
	 *
	 * @return The number of deleted blobs
	 */
	public static int collectGarbage(@NonNull Services services, @NonNull Duration minAge) throws SqlException {
		Instant threshold = Instant.now().minus(minAge);
		int deleted = 0;
		for (RepositoryEntity repository : services.repositories().list()) {
			if (!RepositoryType.OCI.id().equals(repository.type())) {
				continue;
			}
			// Manifest pushes wait while the references are read and the blobs are deleted
			Lock lock = REFERENCE_LOCKS.get(repository.name()).writeLock();
			lock.lock();
			try {
				Set<String> referenced = referencedDigests(services, repository);
				for (FileEntity file : services.artifacts().list(repository.name(), "blobs/sha256/")) {
					String digest = "sha256:" + file.path().substring("blobs/sha256/".length());
					if (file.createdAt().isBefore(threshold) && !referenced.contains(digest) && services.artifacts().delete(repository.name(), file.path())) {
						deleted++;
					}
				}
			} finally {
				lock.unlock();
			}
		}
		return deleted;
	}
	
	/**
	 * Returns the digests of all blobs referenced by a manifest of the repository.<br>
	 */
	@NonNull
	private static Set<String> referencedDigests(@NonNull Services services, @NonNull RepositoryEntity repository) throws SqlException {
		Set<String> referenced = new HashSet<>();
		List<String> images = services.packages().packageNames(repository.name());
		for (List<PackageVersionEntity> versions : services.packages().versions(repository.name(), images).values()) {
			for (PackageVersionEntity version : versions) {
				Json.parseObject(version.metadata()).path("blobs").forEach(digest -> referenced.add(digest.asString()));
			}
		}
		return referenced;
	}
	
	/**
	 * Returns the error code of the distribution spec for a status, null if the spec defines none.<br>
	 */
	@Nullable
	private static String errorCode(int status) {
		return switch (status) {
			case 401 -> "UNAUTHORIZED";
			case 403 -> "DENIED";
			case 405 -> "UNSUPPORTED";
			case 413 -> "SIZE_INVALID";
			case 429 -> "TOOMANYREQUESTS";
			default -> null;
		};
	}
	
	/**
	 * Converts a plain error to the error format of the distribution spec and replaces the challenge of a 401 with the bearer challenge of the registry.<br>
	 */
	@NonNull
	HttpError translate(@NonNull Context ctx, @NonNull HttpError error, @Nullable RepositoryEntity repository) {
		// The token endpoint is not part of the distribution api, invalid credentials keep the basic challenge there
		if (Boolean.TRUE.equals(ctx.attribute(TOKEN_ATTRIBUTE))) {
			return error;
		}
		Map<String, String> headers = new HashMap<>(error.headers());
		if (error.status() == 401) {
			headers.keySet().removeIf("WWW-Authenticate"::equalsIgnoreCase);
			headers.put("WWW-Authenticate", this.bearerChallenge(ctx, repository));
		}
		String code = errorCode(error.status());
		boolean formatted = error.contentType() != null && error.contentType().startsWith("application/json");
		if (formatted || code == null) {
			return error.status() == 401 ? HttpError.json(401, error.getMessage(), Objects.requireNonNullElse(error.body(), ""), headers) : error;
		}
		ObjectNode root = Json.object();
		root.putArray("errors").addObject().put("code", code).put("message", Objects.requireNonNullElse(error.body(), error.getMessage()));
		return HttpError.json(error.status(), error.getMessage(), Json.write(root), headers);
	}
	
	/**
	 * The challenge of the token flow, clients fetch a token from the realm (anonymously if they have no credentials) and send it as bearer token.<br>
	 * A {@code Basic} challenge does not work for anonymous clients: Docker aborts every request without stored credentials after one.<br>
	 */
	@NonNull
	private String bearerChallenge(@NonNull Context ctx, @Nullable RepositoryEntity repository) {
		return "Bearer realm=\"" + this.tokenUrl(ctx, repository) + "\",service=\"" + TOKEN_SERVICE + "\"";
	}
	
	@NonNull
	private String tokenUrl(@NonNull Context ctx, @Nullable RepositoryEntity repository) {
		if (repository == null || this.isRoot(ctx)) {
			return Requests.baseUrl(ctx, this.services.config()) + "/v2/token";
		}
		return this.repositoryUrl(ctx, repository) + "/v2/token";
	}
	
	@Override
	@NonNull
	protected String challenge(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		return this.bearerChallenge(ctx, repository);
	}
	
	/**
	 * Hands out a bearer token for the credentials of the request, an anonymous token without credentials.<br>
	 * The token is not limited to the requested scope, every request is still authorized against the permissions of its user.<br>
	 */
	public void token(@NonNull Context ctx) throws SqlException {
		ctx.attribute(TOKEN_ATTRIBUTE, true);
		String method = Requests.method(ctx);
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			throw HttpError.methodNotAllowed();
		}
		String token = this.services.auth().bearerToken(ctx, TOKEN_LIFETIME);
		ObjectNode root = Json.object();
		root.put("token", token);
		root.put("access_token", token);
		root.put("expires_in", TOKEN_LIFETIME.minus(TOKEN_EXPIRY_MARGIN).toSeconds());
		root.put("issued_at", java.time.format.DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
		ctx.header("Cache-Control", "no-store");
		json(ctx, 200, root);
	}
	
	@Override
	public @NonNull RepositoryType type() {
		return RepositoryType.OCI;
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		try {
			this.dispatch(ctx, repository, rawPath);
		} catch (HttpError e) {
			throw this.translate(ctx, e, repository);
		}
	}
	
	private void dispatch(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		ctx.header(API_VERSION_HEADER, API_VERSION);
		List<String> segments = Requests.decodedSegments(rawPath);
		if (segments.isEmpty() || !"v2".equals(segments.getFirst())) {
			throw error(404, "NAME_UNKNOWN", "OCI repositories are served below /v2/");
		}
		List<String> rest = segments.subList(1, segments.size());
		String method = Requests.method(ctx);
		boolean read = "GET".equals(method) || "HEAD".equals(method);
		int size = rest.size();
		
		if (rest.isEmpty()) {
			if (!read) {
				throw HttpError.methodNotAllowed();
			}
			this.ping(ctx);
		} else if (rest.equals(List.of("token")) && !this.isRoot(ctx)) {
			this.token(ctx);
		} else if (rest.equals(List.of("_catalog")) && !this.isRoot(ctx)) {
			if (!read) {
				throw HttpError.methodNotAllowed();
			}
			this.authorize(ctx, repository, AccessLevel.READ);
			SortedSet<String> names = new TreeSet<>(this.services.packages().packageNames(repository.name()));
			ObjectNode root = Json.object();
			ArrayNode repositories = root.putArray("repositories");
			page(ctx, names, this.registryUrl(ctx, repository) + "_catalog").forEach(repositories::add);
			json(ctx, 200, root);
		} else if (size >= 3 && "blobs".equals(rest.get(size - 2)) && "uploads".equals(rest.get(size - 1))) {
			if (!"POST".equals(method)) {
				throw HttpError.methodNotAllowed();
			}
			this.startUpload(ctx, repository, this.image(rest, size - 2));
		} else if (size >= 4 && "blobs".equals(rest.get(size - 3)) && "uploads".equals(rest.get(size - 2))) {
			this.upload(ctx, repository, this.image(rest, size - 3), rest.getLast(), method);
		} else if (size >= 2 && "manifests".equals(rest.get(size - 2))) {
			this.manifest(ctx, repository, this.image(rest, size - 2), rest.getLast(), method);
		} else if (size >= 2 && "blobs".equals(rest.get(size - 2))) {
			this.blob(ctx, repository, this.image(rest, size - 2), rest.getLast(), method);
		} else if (size >= 2 && "referrers".equals(rest.get(size - 2))) {
			if (!read) {
				throw HttpError.methodNotAllowed();
			}
			this.referrers(ctx, repository, this.image(rest, size - 2), rest.getLast());
		} else if (size >= 3 && "tags".equals(rest.get(size - 2)) && "list".equals(rest.get(size - 1))) {
			if (!read) {
				throw HttpError.methodNotAllowed();
			}
			this.tags(ctx, repository, this.image(rest, size - 2));
		} else {
			throw error(404, "NAME_UNKNOWN", "Unknown registry endpoint");
		}
	}
	
	/**
	 * Answers the version check of a client, an anonymous client receives the bearer challenge so it learns where to fetch tokens.<br>
	 * A client without credentials fetches an anonymous token and reads public repositories with it, a client with credentials
	 * fetches a token for them, which is why the ping must not answer anonymous clients with 200.<br>
	 */
	public void ping(@NonNull Context ctx) throws SqlException {
		ctx.header(API_VERSION_HEADER, API_VERSION);
		if (this.services.auth().authenticate(ctx) == null) {
			throw HttpError.unauthorized("Bearer");
		}
		json(ctx, 200, Json.object());
	}
	
	/**
	 * Lists the images of all OCI repositories readable by the caller, prefixed with the repository name.<br>
	 */
	public void catalog(@NonNull Context ctx) throws SqlException {
		ctx.header(API_VERSION_HEADER, API_VERSION);
		Principal principal = this.services.auth().authenticate(ctx);
		SortedSet<String> names = new TreeSet<>();
		for (RepositoryEntity repository : this.services.repositories().list()) {
			if (RepositoryType.OCI.id().equals(repository.type()) && this.services.auth().canRead(principal, repository)) {
				for (String image : this.services.packages().packageNames(repository.name())) {
					names.add(repository.name() + "/" + image);
				}
			}
		}
		ObjectNode root = Json.object();
		ArrayNode repositories = root.putArray("repositories");
		page(ctx, names, Requests.baseUrl(ctx, this.services.config()) + "/v2/_catalog").forEach(repositories::add);
		json(ctx, 200, root);
	}
	
	private boolean isRoot(@NonNull Context ctx) {
		return Boolean.TRUE.equals(ctx.attribute(ROOT_ATTRIBUTE));
	}
	
	@NonNull
	private String image(@NonNull List<String> rest, int end) {
		String image = String.join("/", rest.subList(0, end));
		if (image.isEmpty()) {
			throw error(404, "NAME_UNKNOWN", "Missing image name, use {repository}/{image}");
		}
		if (!isValidName(image)) {
			throw error(400, "NAME_INVALID", "Invalid image name: " + image);
		}
		return image;
	}
	
	/**
	 * Returns the url of the registry api of the repository with a trailing slash, the image name is appended to it.<br>
	 */
	@NonNull
	private String registryUrl(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		if (this.isRoot(ctx)) {
			return Requests.baseUrl(ctx, this.services.config()) + "/v2/" + repository.name() + "/";
		}
		return this.repositoryUrl(ctx, repository) + "/v2/";
	}
	
	/**
	 * Returns the image name as the client sees it.<br>
	 */
	@NonNull
	private String clientName(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image) {
		return this.isRoot(ctx) ? repository.name() + "/" + image : image;
	}
	
	@NonNull
	private String requireDigest(@NonNull String digest) {
		if (!DIGEST.matcher(digest).matches()) {
			throw error(400, "DIGEST_INVALID", "Invalid or unsupported digest, only sha256 is supported: " + digest);
		}
		return digest;
	}
	
	//region Manifests
	private void manifest(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String reference, @NonNull String method) throws Exception {
		switch (method) {
			case "GET", "HEAD" -> this.getManifest(ctx, repository, image, reference);
			case "PUT" -> this.putManifest(ctx, repository, image, reference);
			case "DELETE" -> this.deleteManifest(ctx, repository, image, reference);
			default -> throw HttpError.methodNotAllowed();
		}
	}
	
	private void getManifest(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String reference) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		String digest = DIGEST.matcher(reference).matches() ? reference : TAG.matcher(reference).matches() ? this.services.packages().tags(repository.name(), image).get(reference) : null;
		FileEntity file = digest == null ? null : this.services.artifacts().get(repository.name(), manifestPath(image, digest));
		if (file == null) {
			throw error(404, "MANIFEST_UNKNOWN", "Unknown manifest " + this.clientName(ctx, repository, image) + ":" + reference);
		}
		ctx.header("Docker-Content-Digest", digest);
		this.services.artifacts().serve(ctx, file, file.contentType());
		// Jetty appends a charset to +json types, clients compare the media type of a manifest literally
		ctx.res().setCharacterEncoding(null);
		ctx.res().setHeader("Content-Type", file.contentType());
	}
	
	private void putManifest(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String reference) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		boolean byDigest = DIGEST.matcher(reference).matches();
		if (!byDigest && !TAG.matcher(reference).matches()) {
			throw error(400, "TAG_INVALID", "Invalid tag: " + reference);
		}
		byte[] body = this.services.bodies().bounded(ctx, MAX_MANIFEST_SIZE);
		String digest = sha256(body);
		if (byDigest && !reference.equals(digest)) {
			throw error(400, "DIGEST_INVALID", "The manifest has the digest " + digest + ", not " + reference);
		}
		
		JsonNode manifest;
		try {
			manifest = Json.parse(body);
		} catch (JacksonException e) {
			throw error(400, "MANIFEST_INVALID", "The manifest is not valid json");
		}
		if (!manifest.isObject() || manifest.path("schemaVersion").asInt(0) != 2) {
			throw error(400, "MANIFEST_INVALID", "Only manifests with schemaVersion 2 are supported");
		}
		String mediaType = mediaType(ctx.header("Content-Type"), manifest);
		// The references are checked and the manifest is committed while no blob of the repository can be deleted
		Lock lock = REFERENCE_LOCKS.get(repository.name()).readLock();
		lock.lock();
		try {
			this.commitManifest(ctx, repository, image, reference, byDigest, principal, body, digest, manifest, mediaType);
		} finally {
			lock.unlock();
		}
	}
	
	private void commitManifest(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String reference, boolean byDigest, @Nullable Principal principal, byte @NonNull [] body, @NonNull String digest, @NonNull JsonNode manifest, @NonNull String mediaType) throws Exception {
		ObjectNode metadata = this.validateReferences(repository, image, manifest, mediaType);
		metadata.put("size", body.length);
		
		if (!byDigest && !repository.allowRedeploy()) {
			String current = this.services.packages().tags(repository.name(), image).get(reference);
			if (current != null && !current.equals(digest)) {
				throw error(409, "DENIED", "Tag " + reference + " already exists, the repository does not allow redeploys");
			}
		}
		
		StoredBlob blob = this.services.artifacts().upload(body);
		String path = manifestPath(image, digest);
		this.services.artifacts().transaction(work -> {
			if (this.services.artifacts().get(work, repository.name(), path) == null) {
				this.services.artifacts().put(work, repository.name(), path, blob, mediaType, image, digest, null, username(principal));
			}
			this.services.packages().ensure(work, repository.name(), image, digest, Json.write(metadata), username(principal));
			if (!byDigest) {
				this.services.packages().setTag(work, repository.name(), image, reference, digest);
			}
			return null;
		});
		
		String subject = Json.text(metadata, "subject");
		if (subject != null) {
			ctx.header("OCI-Subject", subject);
		}
		ctx.header("Location", this.registryUrl(ctx, repository) + image + "/manifests/" + digest);
		ctx.header("Docker-Content-Digest", digest);
		ctx.status(201).result("");
	}
	
	/**
	 * Checks that the blobs (or for an index the manifests) referenced by the manifest exist and returns the metadata of the manifest.<br>
	 */
	@NonNull
	private ObjectNode validateReferences(@NonNull RepositoryEntity repository, @NonNull String image, @NonNull JsonNode manifest, @NonNull String mediaType) throws SqlException {
		ObjectNode metadata = Json.object();
		metadata.put("mediaType", mediaType);
		ArrayNode blobs = metadata.putArray("blobs");
		if (INDEX_TYPES.contains(mediaType)) {
			JsonNode manifests = manifest.path("manifests");
			if (!manifests.isArray()) {
				throw error(400, "MANIFEST_INVALID", "An index needs a manifests list");
			}
			for (JsonNode child : manifests) {
				String digest = this.requireDigest(Json.text(child, "digest", ""));
				if (this.services.artifacts().get(repository.name(), manifestPath(image, digest)) == null) {
					throw error(400, "MANIFEST_BLOB_UNKNOWN", "Referenced manifest " + digest + " is unknown, push it first");
				}
			}
		} else {
			if (!manifest.path("config").isObject() || !manifest.path("layers").isArray()) {
				throw error(400, "MANIFEST_INVALID", "An image manifest needs a config and a layers list");
			}
			for (String digest : referencedBlobs(manifest)) {
				this.requireDigest(digest);
				if (this.services.artifacts().get(repository.name(), blobPath(digest)) == null) {
					throw error(400, "MANIFEST_BLOB_UNKNOWN", "Referenced blob " + digest + " is unknown, push it first");
				}
				blobs.add(digest);
			}
		}
		
		String artifactType = Json.text(manifest, "artifactType");
		if (artifactType == null && OCI_MANIFEST.equals(mediaType)) {
			artifactType = Json.text(manifest.path("config"), "mediaType");
		}
		if (artifactType != null) {
			metadata.put("artifactType", artifactType);
		}
		String subject = Json.text(manifest.path("subject"), "digest");
		if (subject != null) {
			metadata.put("subject", this.requireDigest(subject));
		}
		if (manifest.path("annotations").isObject()) {
			metadata.set("annotations", manifest.get("annotations"));
		}
		return metadata;
	}
	
	private void deleteManifest(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String reference) throws Exception {
		this.authorize(ctx, repository, AccessLevel.DELETE);
		if (!DIGEST.matcher(reference).matches()) {
			if (!TAG.matcher(reference).matches() || !this.services.packages().deleteTag(repository.name(), image, reference)) {
				throw error(404, "MANIFEST_UNKNOWN", "Unknown tag " + reference);
			}
			ctx.status(202).result("");
			return;
		}
		boolean deleted = this.services.artifacts().transaction(work -> {
			boolean removed = this.services.packages().delete(work, repository.name(), image, reference);
			removed |= this.services.artifacts().delete(work, repository.name(), manifestPath(image, reference));
			for (Map.Entry<String, String> tag : this.services.packages().tags(work, repository.name(), image).entrySet()) {
				if (tag.getValue().equals(reference)) {
					this.services.packages().deleteTag(work, repository.name(), image, tag.getKey());
				}
			}
			return removed;
		});
		if (!deleted) {
			throw error(404, "MANIFEST_UNKNOWN", "Unknown manifest " + reference);
		}
		ctx.status(202).result("");
	}
	
	private void tags(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		SortedSet<String> tags = new TreeSet<>(this.services.packages().tags(repository.name(), image).keySet());
		if (tags.isEmpty() && this.services.packages().versions(repository.name(), image).isEmpty()) {
			throw error(404, "NAME_UNKNOWN", "Unknown image " + this.clientName(ctx, repository, image));
		}
		ObjectNode root = Json.object();
		root.put("name", this.clientName(ctx, repository, image));
		ArrayNode list = root.putArray("tags");
		page(ctx, tags, this.registryUrl(ctx, repository) + image + "/tags/list").forEach(list::add);
		json(ctx, 200, root);
	}
	//endregion
	
	private void referrers(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String digest) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		this.requireDigest(digest);
		String artifactType = ctx.queryParam("artifactType");
		ObjectNode root = Json.object();
		root.put("schemaVersion", 2);
		root.put("mediaType", OCI_INDEX);
		ArrayNode manifests = root.putArray("manifests");
		for (PackageVersionEntity version : this.services.packages().versions(repository.name(), image)) {
			ObjectNode metadata = Json.parseObject(version.metadata());
			if (!digest.equals(Json.text(metadata, "subject")) || (artifactType != null && !artifactType.equals(Json.text(metadata, "artifactType")))) {
				continue;
			}
			ObjectNode descriptor = manifests.addObject();
			descriptor.put("mediaType", Json.text(metadata, "mediaType", OCI_MANIFEST));
			descriptor.put("digest", version.version());
			descriptor.put("size", metadata.path("size").asLong());
			if (metadata.has("artifactType")) {
				descriptor.put("artifactType", Json.text(metadata, "artifactType"));
			}
			if (metadata.has("annotations")) {
				descriptor.set("annotations", metadata.get("annotations"));
			}
		}
		if (artifactType != null) {
			ctx.header("OCI-Filters-Applied", "artifactType");
		}
		ctx.status(200).contentType(OCI_INDEX).result(Json.write(root));
		// Jetty appends a charset to +json types, oras compares the media type of the referrers response literally
		ctx.res().setCharacterEncoding(null);
		ctx.res().setHeader("Content-Type", OCI_INDEX);
	}
	
	//region Blobs
	private void blob(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String digest, @NonNull String method) throws Exception {
		switch (method) {
			case "GET", "HEAD" -> {
				this.authorize(ctx, repository, AccessLevel.READ);
				FileEntity file = this.services.artifacts().get(repository.name(), blobPath(this.requireDigest(digest)));
				if (file == null) {
					throw error(404, "BLOB_UNKNOWN", "Unknown blob " + digest);
				}
				ctx.header("Docker-Content-Digest", digest);
				this.services.artifacts().serve(ctx, file, BLOB_CONTENT_TYPE);
			}
			case "DELETE" -> {
				this.authorize(ctx, repository, AccessLevel.DELETE);
				this.requireDigest(digest);
				Lock lock = REFERENCE_LOCKS.get(repository.name()).writeLock();
				lock.lock();
				try {
					// Deleting a referenced blob is allowed by the spec, but leaves a manifest that is served and can not be pulled
					if (referencedDigests(this.services, repository).contains(digest)) {
						throw error(409, "DENIED", "Blob " + digest + " is referenced by a manifest, delete the manifest first");
					}
					if (!this.services.artifacts().delete(repository.name(), blobPath(digest))) {
						throw error(404, "BLOB_UNKNOWN", "Unknown blob " + digest);
					}
				} finally {
					lock.unlock();
				}
				ctx.status(202).result("");
			}
			default -> throw HttpError.methodNotAllowed();
		}
	}
	
	private void startUpload(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		String blobUrl = this.registryUrl(ctx, repository) + image + "/blobs/";
		
		// Blobs are shared by all images of a repository, a mount from another image of it always succeeds
		String mount = ctx.queryParam("mount");
		if (mount != null && DIGEST.matcher(mount).matches() && this.services.artifacts().get(repository.name(), blobPath(mount)) != null) {
			this.created(ctx, blobUrl, mount);
			return;
		}
		
		String digest = ctx.queryParam("digest");
		if (digest != null) {
			this.requireDigest(digest);
			StoredBlob blob;
			try (InputStream input = ctx.bodyInputStream()) {
				blob = this.services.artifacts().upload(input);
			}
			this.commitBlob(repository, principal, blob, digest);
			this.created(ctx, blobUrl, digest);
			return;
		}
		
		this.pruneUploads();
		if (this.uploads.size() >= MAX_UPLOAD_SESSIONS) {
			throw error(429, "TOOMANYREQUESTS", "Too many concurrent uploads, try again later");
		}
		String id = UUID.randomUUID().toString();
		Path file = this.services.artifacts().blobStore().createTemporaryFile("oci-upload-");
		this.uploads.put(id, new UploadSession(repository.name(), image, username(principal), file));
		ctx.header("Location", blobUrl + "uploads/" + id);
		ctx.header("Range", "0-0");
		ctx.header("Docker-Upload-UUID", id);
		ctx.status(202).result("");
	}
	
	private void upload(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String image, @NonNull String id, @NonNull String method) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		UploadSession session = this.uploads.get(id);
		if (session == null || !session.repository.equals(repository.name()) || !session.image.equals(image) || !Objects.equals(session.username, username(principal)) || !Files.exists(session.file)) {
			throw error(404, "BLOB_UPLOAD_UNKNOWN", "Unknown upload " + id);
		}
		String uploadUrl = this.registryUrl(ctx, repository) + image + "/blobs/uploads/" + id;
		ctx.header("Docker-Upload-UUID", id);
		
		synchronized (session) {
			switch (method) {
				case "GET", "HEAD" -> {
					ctx.header("Location", uploadUrl);
					ctx.header("Range", range(session.size));
					ctx.status(204).result("");
				}
				case "PATCH" -> {
					String contentRange = ctx.header("Content-Range");
					if (contentRange != null && !contentRange.isBlank()) {
						String start = contentRange.strip().replaceFirst("^bytes[ =]", "").split("-", 2)[0].strip();
						if (!start.equals(String.valueOf(session.size))) {
							ctx.header("Location", uploadUrl);
							ctx.header("Range", range(session.size));
							throw error(416, "BLOB_UPLOAD_INVALID", "Chunk does not start at offset " + session.size);
						}
					}
					this.append(ctx, session);
					ctx.header("Location", uploadUrl);
					ctx.header("Range", range(session.size));
					ctx.status(202).result("");
				}
				case "PUT" -> {
					String digest = this.requireDigest(Objects.requireNonNullElse(ctx.queryParam("digest"), ""));
					this.append(ctx, session);
					StoredBlob blob;
					try (InputStream input = Files.newInputStream(session.file)) {
						blob = this.services.artifacts().upload(input);
					}
					this.closeUpload(id, session);
					this.commitBlob(repository, principal, blob, digest);
					this.created(ctx, this.registryUrl(ctx, repository) + image + "/blobs/", digest);
				}
				case "DELETE" -> {
					this.closeUpload(id, session);
					ctx.status(204).result("");
				}
				default -> throw HttpError.methodNotAllowed();
			}
		}
	}
	
	private void append(@NonNull Context ctx, @NonNull UploadSession session) throws IOException {
		long max = this.services.artifacts().blobStore().maxSize();
		try (InputStream input = ctx.bodyInputStream(); OutputStream output = Files.newOutputStream(session.file, StandardOpenOption.APPEND)) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				if (session.size + read > max) {
					throw HttpError.payloadTooLarge();
				}
				output.write(buffer, 0, read);
				session.size += read;
			}
		}
		session.lastUsed = Instant.now();
	}
	
	/**
	 * Records an uploaded blob in the repository after checking it has the digest the client announced.<br>
	 */
	private void commitBlob(@NonNull RepositoryEntity repository, @Nullable Principal principal, @NonNull StoredBlob blob, @NonNull String digest) throws SqlException {
		if (!digest.equals("sha256:" + blob.sha256())) {
			// The stored blob is unreferenced and removed by the orphan sweep
			throw error(400, "DIGEST_INVALID", "The uploaded content has the digest sha256:" + blob.sha256() + ", not " + digest);
		}
		String path = blobPath(digest);
		try {
			this.services.artifacts().transaction(work -> {
				if (this.services.artifacts().get(work, repository.name(), path) == null) {
					this.services.artifacts().put(work, repository.name(), path, blob, BLOB_CONTENT_TYPE, null, null, null, username(principal));
				}
				return null;
			});
		} catch (SqlException e) {
			// A concurrent upload of the same blob was committed first
			if (!SqlErrors.isUniqueViolation(e)) {
				throw e;
			}
		}
	}
	
	private void created(@NonNull Context ctx, @NonNull String blobUrl, @NonNull String digest) {
		ctx.header("Location", blobUrl + digest);
		ctx.header("Docker-Content-Digest", digest);
		ctx.status(201).result("");
	}
	
	private void closeUpload(@NonNull String id, @NonNull UploadSession session) {
		this.uploads.remove(id);
		try {
			Files.deleteIfExists(session.file);
		} catch (IOException ignored) {}
	}
	//endregion
	
	/**
	 * Removes abandoned uploads, their temporary file is deleted by the blob store maintenance at the same age.<br>
	 */
	private void pruneUploads() {
		Instant threshold = Instant.now().minus(UPLOAD_MAX_AGE);
		this.uploads.entrySet().removeIf(entry -> entry.getValue().lastUsed.isBefore(threshold) || !Files.exists(entry.getValue().file));
	}
	
	/**
	 * A chunked blob upload, the received bytes are appended to a temporary file.<br>
	 */
	private static final class UploadSession {
		
		private final String repository;
		private final String image;
		private final @Nullable String username;
		private final Path file;
		private long size;
		private volatile Instant lastUsed = Instant.now();
		
		private UploadSession(@NonNull String repository, @NonNull String image, @Nullable String username, @NonNull Path file) {
			this.repository = repository;
			this.image = image;
			this.username = username;
			this.file = file;
		}
	}
}
