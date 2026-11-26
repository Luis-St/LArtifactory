package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.SqlErrors;
import net.luis.artifactory.database.entity.FileEntity;
import net.luis.artifactory.database.entity.RepositoryEntity;
import net.luis.artifactory.handler.format.DebianPackage.Control;
import net.luis.artifactory.handler.format.DebianPackage.InvalidPackageException;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.DebianSigningKey;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.artifactory.util.Json;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Debian (APT) repository of binary packages.<br>
 * <ul>
 *     <li>Upload: {@code PUT {base}/debian/{repo}/pool/{distribution}/{component}/{file}.deb}</li>
 *     <li>Source list: {@code deb {base}/debian/{repo} {distribution} {component}}</li>
 *     <li>Signing key: {@code {base}/debian/{repo}/public.key}</li>
 * </ul>
 * <p>
 *     Packages are stored at {@code pool/{distribution}/{component}/{initial}/{package}/{package}_{version}_{architecture}.deb}.
 *     The indexes below {@code dists/} are generated from the stored packages and kept in memory,
 *     they are regenerated whenever the packages of a distribution changed, no matter which api changed them.
 * </p>
 */
public class DebianHandler extends FormatHandler {

	public static final String CONTENT_TYPE = "application/vnd.debian.binary-package";
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
	private static final Pattern PACKAGE = Pattern.compile("[a-z0-9][a-z0-9+.-]{1,127}");
	private static final Pattern VERSION = Pattern.compile("(?:[0-9]+:)?[0-9A-Za-z][A-Za-z0-9.+~-]{0,127}");
	private static final Pattern ARCHITECTURE = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
	/**
	 * Fields of a {@code Packages} entry that describe the stored file, a control file can not set them.<br>
	 */
	private static final Set<String> FILE_FIELDS = Set.of("Filename", "Size", "MD5sum", "SHA1", "SHA256", "SHA512");
	private static final DateTimeFormatter RELEASE_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);
	private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
	private final Map<String, Object> locks = new ConcurrentHashMap<>();
	private volatile DebianSigningKey signingKey;

	public DebianHandler(@NonNull Services services) {
		super(services);
	}

	/**
	 * Returns the path a package is stored at, the epoch is not part of the file name as in the Debian archive.<br>
	 */
	@NonNull
	public static String poolPath(@NonNull String distribution, @NonNull String component, @NonNull String packageName, @NonNull String version, @NonNull String architecture) {
		String initial = packageName.startsWith("lib") && packageName.length() > 3 ? packageName.substring(0, 4) : packageName.substring(0, 1);
		int colon = version.indexOf(':');
		String fileVersion = colon < 0 ? version : version.substring(colon + 1);
		return "pool/" + distribution + "/" + component + "/" + initial + "/" + packageName + "/" + packageName + "_" + fileVersion + "_" + architecture + ".deb";
	}

	/**
	 * Builds the {@code Packages} entry of a stored package.<br>
	 */
	@NonNull
	static String packagesEntry(@NonNull String control, @NonNull FileEntity file) throws InvalidPackageException {
		StringBuilder entry = new StringBuilder(Control.parse(control).textWithout(FILE_FIELDS));
		entry.append("Filename: ").append(file.path()).append('\n');
		entry.append("Size: ").append(file.size()).append('\n');
		entry.append("MD5sum: ").append(file.md5()).append('\n');
		entry.append("SHA1: ").append(file.sha1()).append('\n');
		entry.append("SHA256: ").append(file.sha256()).append('\n');
		if (file.sha512() != null) {
			entry.append("SHA512: ").append(file.sha512()).append('\n');
		}
		return entry.toString();
	}

	@NonNull
	private static String hex(@NonNull String algorithm, byte @NonNull [] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static byte @NonNull [] gzip(byte @NonNull [] data) {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			// The gzip header of the jdk has no modification time, so equal indexes compress to equal bytes
			try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
				gzip.write(data);
			}
			return output.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@NonNull
	private static String requireName(@NonNull String kind, @NonNull String value) {
		if (!NAME.matcher(value).matches()) {
			throw HttpError.badRequest("Invalid " + kind + ": " + value);
		}
		return value;
	}

	@NonNull
	private static String required(@NonNull Control control, @NonNull String field, @NonNull Pattern pattern) throws InvalidPackageException {
		String value = control.get(field);
		if (value == null || value.isBlank()) {
			throw new InvalidPackageException("Control file has no " + field + " field");
		}
		if (!pattern.matcher(value).matches()) {
			throw new InvalidPackageException("Invalid " + field + " in control file: " + value);
		}
		return value;
	}

	@Override
	@NonNull
	public RepositoryType type() {
		return RepositoryType.DEBIAN;
	}

	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = Requests.decodedSegments(rawPath);
		String method = Requests.method(ctx);
		boolean pool = !segments.isEmpty() && "pool".equals(segments.getFirst());

		if (("PUT".equals(method) || "POST".equals(method)) && pool && segments.size() >= 4) {
			this.upload(ctx, repository, segments);
			return;
		}
		if ("DELETE".equals(method) && pool) {
			this.delete(ctx, repository, String.join("/", segments));
			return;
		}
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			throw HttpError.methodNotAllowed();
		}
		this.authorize(ctx, repository, AccessLevel.READ);

		if (segments.equals(List.of("public.key"))) {
			byte[] key = this.signingKey().armoredPublicKey().getBytes(StandardCharsets.US_ASCII);
			this.serveBytes(ctx, key, "application/pgp-keys", null);
		} else if (pool) {
			FileEntity file = this.services.artifacts().get(repository.name(), String.join("/", segments));
			if (file == null) {
				throw HttpError.notFound();
			}
			this.services.artifacts().serve(ctx, file, CONTENT_TYPE);
		} else if (segments.size() >= 3 && "dists".equals(segments.getFirst())) {
			this.index(ctx, repository, segments.get(1), segments.subList(2, segments.size()));
		} else {
			throw HttpError.notFound();
		}
	}

	@NonNull
	private DebianSigningKey signingKey() {
		DebianSigningKey key = this.signingKey;
		if (key == null) {
			synchronized (this) {
				key = this.signingKey;
				if (key == null) {
					key = DebianSigningKey.load(this.services.config().debianSigningKey(), this.services.config().debianSigningPassphrase(), this.services.config().storagePath());
					this.signingKey = key;
				}
			}
		}
		return key;
	}

	private void index(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String distribution, @NonNull List<String> rest) throws Exception {
		if (!NAME.matcher(distribution).matches()) {
			throw HttpError.notFound();
		}
		Snapshot snapshot = this.snapshot(repository, distribution);
		if (snapshot == null) {
			throw HttpError.notFound("No such distribution: " + distribution);
		}
		String path = String.join("/", rest);
		byte[] content = snapshot.files().get(path);
		// Clients that support Acquire-By-Hash fetch {component}/binary-{arch}/by-hash/{algorithm}/{digest},
		// the previous generation is kept so a client that fetched the old InRelease still finds its indexes
		if (content == null && rest.size() == 5 && "by-hash".equals(rest.get(2))) {
			String key = rest.get(0) + "/" + rest.get(1) + "/" + rest.get(3) + "/" + rest.get(4);
			content = snapshot.byHash().get(key);
			if (content == null && snapshot.previous() != null) {
				content = snapshot.previous().byHash().get(key);
			}
		}
		if (content == null) {
			throw HttpError.notFound();
		}
		String name = rest.getLast();
		String contentType = name.endsWith(".gz") ? "application/gzip" : "Release.gpg".equals(name) ? "application/pgp-signature" : "text/plain; charset=utf-8";
		ctx.header("Cache-Control", "no-cache");
		this.serveBytes(ctx, content, contentType, snapshot.date());
	}

	private void serveBytes(@NonNull Context ctx, byte @NonNull [] content, @NonNull String contentType, @Nullable Instant lastModified) {
		String etag = "\"" + hex("SHA-256", content) + "\"";
		ctx.header("ETag", etag);
		if (lastModified != null) {
			ctx.header("Last-Modified", Requests.httpDate(lastModified));
		}
		if (etag.equals(ctx.header("If-None-Match")) || (lastModified != null && ctx.header("If-None-Match") == null && this.notModifiedSince(ctx.header("If-Modified-Since"), lastModified))) {
			ctx.status(304);
			return;
		}
		ctx.status(200).contentType(contentType);
		ctx.header("Content-Length", String.valueOf(content.length));
		if (!Requests.isHead(ctx)) {
			ctx.result(content);
		}
	}

	private boolean notModifiedSince(@Nullable String header, @NonNull Instant lastModified) {
		if (header == null) {
			return false;
		}
		try {
			Instant since = ZonedDateTime.parse(header.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			return !lastModified.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).isAfter(since);
		} catch (DateTimeParseException e) {
			return false;
		}
	}

	/**
	 * Returns the indexes of a distribution, regenerated if its packages changed since they were generated.<br>
	 *
	 * @return The indexes, null if the distribution holds no packages
	 */
	@Nullable
	private Snapshot snapshot(@NonNull RepositoryEntity repository, @NonNull String distribution) throws Exception {
		List<FileEntity> files = this.services.artifacts().list(repository.name(), "pool/" + distribution + "/");
		if (files.isEmpty()) {
			return null;
		}
		StringBuilder state = new StringBuilder();
		for (FileEntity file : files) {
			state.append(file.path()).append('\0').append(file.sha256()).append('\0').append(file.createdAt().toEpochMilli()).append('\n');
		}
		String fingerprint = hex("SHA-256", state.toString().getBytes(StandardCharsets.UTF_8));
		String key = repository.name() + "\0" + distribution;
		Snapshot current = this.snapshots.get(key);
		if (current != null && current.fingerprint().equals(fingerprint)) {
			return current;
		}
		synchronized (this.locks.computeIfAbsent(key, _ -> new Object())) {
			current = this.snapshots.get(key);
			if (current != null && current.fingerprint().equals(fingerprint)) {
				return current;
			}
			Snapshot generated = this.generate(repository, distribution, files, fingerprint, current == null ? null : current.withoutPrevious());
			this.snapshots.put(key, generated);
			return generated;
		}
	}

	@NonNull
	private Snapshot generate(@NonNull RepositoryEntity repository, @NonNull String distribution, @NonNull List<FileEntity> files, @NonNull String fingerprint, @Nullable Snapshot previous) throws Exception {
		// component -> architecture -> entries sorted by package, version and architecture
		Map<String, Map<String, SortedMap<String, String>>> entries = new TreeMap<>();
		Map<String, SortedMap<String, String>> independent = new TreeMap<>();
		SortedSet<String> architectures = new TreeSet<>(Set.of("all"));
		Instant date = Instant.EPOCH;
		for (FileEntity file : files) {
			ObjectNode metadata = Json.parseObject(file.metadata());
			String component = Json.text(metadata, "component");
			String architecture = Json.text(metadata, "architecture");
			String control = Json.text(metadata, "control");
			if (component == null || architecture == null || control == null) {
				continue;
			}
			String sortKey = file.packageName() + "\0" + file.version() + "\0" + architecture + "\0" + file.path();
			String entry = packagesEntry(control, file);
			entries.computeIfAbsent(component, _ -> new TreeMap<>());
			if ("all".equals(architecture)) {
				independent.computeIfAbsent(component, _ -> new TreeMap<>()).put(sortKey, entry);
			} else {
				architectures.add(architecture);
				entries.get(component).computeIfAbsent(architecture, _ -> new TreeMap<>()).put(sortKey, entry);
			}
			if (file.createdAt().isAfter(date)) {
				date = file.createdAt();
			}
		}
		date = date.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

		Map<String, byte[]> generated = new LinkedHashMap<>();
		Map<String, byte[]> byHash = new HashMap<>();
		for (String component : entries.keySet()) {
			for (String architecture : architectures) {
				// Architecture independent packages are listed in binary-all and in every binary-{arch} (No-Support-for-Architecture-all)
				SortedMap<String, String> listed = new TreeMap<>(independent.getOrDefault(component, Collections.emptySortedMap()));
				if (!"all".equals(architecture)) {
					listed.putAll(entries.get(component).getOrDefault(architecture, Collections.emptySortedMap()));
				}
				byte[] packages = String.join("\n", listed.values()).getBytes(StandardCharsets.UTF_8);
				String directory = component + "/binary-" + architecture;
				for (Map.Entry<String, byte[]> index : Map.of("Packages", packages, "Packages.gz", gzip(packages)).entrySet()) {
					generated.put(directory + "/" + index.getKey(), index.getValue());
					byHash.put(directory + "/SHA256/" + hex("SHA-256", index.getValue()), index.getValue());
					byHash.put(directory + "/SHA512/" + hex("SHA-512", index.getValue()), index.getValue());
				}
			}
		}

		StringBuilder release = new StringBuilder();
		release.append("Origin: ").append(repository.name()).append('\n');
		release.append("Label: ").append(repository.name()).append('\n');
		release.append("Suite: ").append(distribution).append('\n');
		release.append("Codename: ").append(distribution).append('\n');
		release.append("Date: ").append(RELEASE_DATE.format(date)).append('\n');
		release.append("Architectures: ").append(String.join(" ", architectures)).append('\n');
		release.append("Components: ").append(String.join(" ", entries.keySet())).append('\n');
		release.append("Acquire-By-Hash: yes\n");
		release.append("No-Support-for-Architecture-all: Packages\n");
		for (Map.Entry<String, String> algorithm : List.of(Map.entry("SHA256", "SHA-256"), Map.entry("SHA512", "SHA-512"))) {
			release.append(algorithm.getKey()).append(":\n");
			for (Map.Entry<String, byte[]> index : new TreeMap<>(generated).entrySet()) {
				release.append(' ').append(hex(algorithm.getValue(), index.getValue())).append(' ').append(index.getValue().length).append(' ').append(index.getKey()).append('\n');
			}
		}
		String releaseText = release.toString();
		DebianSigningKey key = this.signingKey();
		generated.put("Release", releaseText.getBytes(StandardCharsets.UTF_8));
		generated.put("Release.gpg", key.detachedSignature(releaseText.getBytes(StandardCharsets.UTF_8), date).getBytes(StandardCharsets.US_ASCII));
		generated.put("InRelease", key.clearSign(releaseText, date).getBytes(StandardCharsets.UTF_8));
		return new Snapshot(fingerprint, date, Map.copyOf(generated), Map.copyOf(byHash), previous);
	}

	private void upload(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<String> segments) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		String distribution = requireName("distribution", segments.get(1));
		String component = requireName("component", segments.get(2));

		StoredBlob blob;
		try (InputStream input = ctx.bodyInputStream()) {
			blob = this.services.artifacts().upload(input);
		}
		try {
			Control control;
			try (InputStream input = this.services.artifacts().blobStore().open(blob.sha256())) {
				control = DebianPackage.readControl(input);
			}
			String packageName = required(control, "Package", PACKAGE);
			String version = required(control, "Version", VERSION);
			String architecture = required(control, "Architecture", ARCHITECTURE);
			String path = poolPath(distribution, component, packageName, version, architecture);

			ObjectNode fileMetadata = Json.object();
			fileMetadata.put("distribution", distribution);
			fileMetadata.put("component", component);
			fileMetadata.put("architecture", architecture);
			fileMetadata.put("control", control.text());
			ObjectNode versionMetadata = Json.object();
			String description = control.get("Description");
			if (description != null) {
				versionMetadata.put("description", description.lines().findFirst().orElse("").strip());
			}
			for (String field : List.of("Maintainer", "Section", "Homepage")) {
				String value = control.get(field);
				if (value != null) {
					versionMetadata.put(field.toLowerCase(Locale.ROOT), value);
				}
			}

			boolean stored = this.services.artifacts().transaction(work -> {
				FileEntity existing = this.services.artifacts().get(work, repository.name(), path);
				if (existing != null && existing.sha256().equals(blob.sha256())) {
					return false;
				}
				if (existing != null && !repository.allowRedeploy()) {
					throw HttpError.conflict("Package already exists: " + path);
				}
				if (existing == null) {
					this.services.artifacts().insert(work, repository.name(), path, blob, CONTENT_TYPE, packageName, version, Json.write(fileMetadata), username(principal));
				} else {
					this.services.artifacts().put(work, repository.name(), path, blob, CONTENT_TYPE, packageName, version, Json.write(fileMetadata), username(principal));
				}
				this.services.packages().upsert(work, repository.name(), packageName, version, Json.write(versionMetadata), username(principal));
				return true;
			});
			if (!stored) {
				this.services.artifacts().releaseBlobs(List.of(PathRepositoryHandler.fakeFile(repository, blob)));
			}

			ObjectNode response = Json.object();
			response.put("path", path);
			response.put("package", packageName);
			response.put("version", version);
			response.put("architecture", architecture);
			response.put("distribution", distribution);
			response.put("component", component);
			json(ctx, stored ? 201 : 200, response);
		} catch (InvalidPackageException e) {
			this.services.artifacts().releaseBlobs(List.of(PathRepositoryHandler.fakeFile(repository, blob)));
			throw HttpError.badRequest(e.getMessage());
		} catch (HttpError | SqlException e) {
			this.services.artifacts().releaseBlobs(List.of(PathRepositoryHandler.fakeFile(repository, blob)));
			if (e instanceof SqlException sql && SqlErrors.isUniqueViolation(sql)) {
				throw HttpError.conflict("Package already exists");
			}
			throw e;
		}
	}

	private void delete(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception {
		this.authorize(ctx, repository, AccessLevel.DELETE);
		boolean deleted = this.services.artifacts().transaction(work -> {
			FileEntity file = this.services.artifacts().get(work, repository.name(), path);
			if (file == null) {
				return false;
			}
			this.services.artifacts().delete(work, repository.name(), path);
			// The version is shared by the architectures and distributions of a package, it goes with its last file
			if (file.packageName() != null && file.version() != null && this.services.artifacts().listByVersion(work, repository.name(), file.packageName(), file.version()).isEmpty()) {
				this.services.packages().delete(work, repository.name(), file.packageName(), file.version());
			}
			return true;
		});
		if (!deleted) {
			throw HttpError.notFound();
		}
		ctx.status(204);
	}

	/**
	 * The generated indexes of one distribution.<br>
	 *
	 * @param fingerprint The hash of the stored packages the indexes were generated from
	 * @param date The time of the newest package, used as {@code Date} and as signature time so equal content gives equal indexes
	 * @param files The indexes by their path below {@code dists/{distribution}/}
	 * @param byHash The {@code Packages} files by {@code {component}/binary-{arch}/{algorithm}/{digest}}
	 * @param previous The indexes generated before, still served by hash
	 */
	private record Snapshot(@NonNull String fingerprint, @NonNull Instant date, @NonNull Map<String, byte[]> files, @NonNull Map<String, byte[]> byHash, @Nullable Snapshot previous) {

		@NonNull
		private Snapshot withoutPrevious() {
			return new Snapshot(this.fingerprint, this.date, this.files, this.byHash, null);
		}
	}
}
