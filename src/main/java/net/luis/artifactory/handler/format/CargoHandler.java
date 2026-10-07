package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.artifactory.storage.StoredBlob;
import net.luis.artifactory.util.Json;
import net.luis.artifactory.util.Versions;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Cargo alternative registry using the sparse index protocol.<br>
 * Index url: {@code sparse+{base}/cargo/{repo}/index/}<br>
 */
public class CargoHandler extends FormatHandler {
	
	private static final Pattern CRATE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
	
	public CargoHandler(@NonNull Services services) {
		super(services);
	}
	
	@Override
	public @NonNull RepositoryType type() {
		return RepositoryType.CARGO;
	}
	
	@Override
	protected @NonNull String challenge(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		return "Cargo login_url=\"" + this.repositoryUrl(ctx, repository) + "/me\"";
	}
	
	/**
	 * Returns the path of the index file of a crate as defined by the Cargo registry index format.<br>
	 */
	public static @NonNull String indexPath(@NonNull String crate) {
		String name = crate.toLowerCase(Locale.ROOT);
		return switch (name.length()) {
			case 1 -> "1/" + name;
			case 2 -> "2/" + name;
			case 3 -> "3/" + name.charAt(0) + "/" + name;
			default -> name.substring(0, 2) + "/" + name.substring(2, 4) + "/" + name;
		};
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = Requests.decodedSegments(rawPath);
		String method = Requests.method(ctx);
		boolean read = "GET".equals(method) || "HEAD".equals(method);
		
		if (segments.equals(List.of("config.json")) || segments.equals(List.of("index", "config.json"))) {
			if (!read) {
				throw HttpError.methodNotAllowed();
			}
			this.authorize(ctx, repository, AccessLevel.READ);
			this.config(ctx, repository);
		} else if (!segments.isEmpty() && segments.getFirst().equals("index") && segments.size() >= 3) {
			if (!read) {
				throw HttpError.methodNotAllowed();
			}
			this.authorize(ctx, repository, AccessLevel.READ);
			String crate = segments.getLast().toLowerCase(Locale.ROOT);
			if (!String.join("/", segments.subList(1, segments.size())).toLowerCase(Locale.ROOT).equals(indexPath(crate))) {
				throw HttpError.notFound();
			}
			this.indexFile(ctx, repository, crate);
		} else if (segments.size() >= 3 && segments.subList(0, 3).equals(List.of("api", "v1", "crates"))) {
			this.api(ctx, repository, segments.subList(3, segments.size()), method);
		} else if (segments.equals(List.of("me"))) {
			text(ctx, 200, "Create an api token in LArtifactory (POST /api/tokens) and run: cargo login --registry <name> <token>");
		} else {
			throw HttpError.notFound();
		}
	}
	
	private void api(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull List<String> rest, @NonNull String method) throws Exception {
		if (rest.isEmpty() && "GET".equals(method)) {
			this.search(ctx, repository);
		} else if (rest.equals(List.of("new")) && "PUT".equals(method)) {
			this.publish(ctx, repository);
		} else if (rest.size() == 3 && rest.get(2).equals("download") && ("GET".equals(method) || "HEAD".equals(method))) {
			this.download(ctx, repository, rest.get(0).toLowerCase(Locale.ROOT), rest.get(1));
		} else if (rest.size() == 3 && rest.get(2).equals("yank") && "DELETE".equals(method)) {
			this.yank(ctx, repository, rest.get(0).toLowerCase(Locale.ROOT), rest.get(1), true);
		} else if (rest.size() == 3 && rest.get(2).equals("unyank") && "PUT".equals(method)) {
			this.yank(ctx, repository, rest.get(0).toLowerCase(Locale.ROOT), rest.get(1), false);
		} else if (rest.size() == 2 && rest.get(1).equals("owners")) {
			this.owners(ctx, repository, rest.getFirst().toLowerCase(Locale.ROOT), method);
		} else {
			throw cargoError(404, "Not found");
		}
	}
	
	//region Read
	private void config(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		String base = this.repositoryUrl(ctx, repository);
		ObjectNode root = Json.object();
		root.put("dl", base + "/api/v1/crates");
		root.put("api", base);
		if (!repository.publicRead()) {
			root.put("auth-required", true);
		}
		json(ctx, 200, root);
	}
	
	private void indexFile(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String crate) throws Exception {
		List<PackageVersionEntity> versions = this.services.packages().versions(repository.name(), crate);
		if (versions.isEmpty()) {
			throw HttpError.notFound();
		}
		StringBuilder builder = new StringBuilder();
		for (PackageVersionEntity version : versions) {
			ObjectNode line = Json.parseObject(version.metadata());
			line.put("yanked", version.yanked());
			line.remove("_publish");
			builder.append(Json.write(line)).append('\n');
		}
		ctx.status(200).contentType("text/plain; charset=utf-8").result(builder.toString());
	}
	
	private void download(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String crate, @NonNull String version) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		FileEntity file = this.services.artifacts().get(repository.name(), cratePath(crate, version));
		if (file == null) {
			throw cargoError(404, "No such package or version");
		}
		this.services.artifacts().serve(ctx, file, "application/octet-stream");
	}
	
	private void search(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		this.authorize(ctx, repository, AccessLevel.READ);
		String query = Objects.requireNonNullElse(ctx.queryParam("q"), "").toLowerCase(Locale.ROOT).strip();
		int perPage = 10;
		try {
			perPage = Math.clamp(Integer.parseInt(Objects.requireNonNullElse(ctx.queryParam("per_page"), "10")), 1, 100);
		} catch (NumberFormatException ignored) {}
		
		ObjectNode root = Json.object();
		ArrayNode crates = root.putArray("crates");
		int total = 0;
		for (Map.Entry<String, List<PackageVersionEntity>> entry : this.services.packages().grouped(repository.name()).entrySet()) {
			if (!query.isEmpty() && !entry.getKey().contains(query.replace('_', '-')) && !entry.getKey().contains(query)) {
				continue;
			}
			total++;
			if (crates.size() >= perPage) {
				continue;
			}
			PackageVersionEntity latest = entry.getValue().stream()
				.filter(version -> !version.yanked())
				.max(Comparator.comparing(PackageVersionEntity::version, Versions.SEMVER))
				.orElse(entry.getValue().getLast());
			ObjectNode metadata = Json.parseObject(latest.metadata());
			crates.addObject()
				.put("name", Json.text(metadata, "name", entry.getKey()))
				.put("max_version", latest.version())
				.put("description", Json.text(metadata.path("_publish"), "description", ""));
		}
		root.putObject("meta").put("total", total);
		json(ctx, 200, root);
	}
	//endregion
	
	//region Write
	private void publish(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		ByteBuffer buffer = ByteBuffer.wrap(this.readBody(ctx)).order(ByteOrder.LITTLE_ENDIAN);
		
		JsonNode payload;
		byte[] crate;
		try {
			// Validate each length against the bytes actually present before allocating, a crafted length prefix
			// would otherwise trigger a multi gigabyte allocation (OutOfMemoryError) from a tiny request body.
			int jsonLength = buffer.getInt();
			if (jsonLength < 0 || jsonLength > buffer.remaining()) {
				throw cargoError(400, "Invalid publish request body");
			}
			byte[] json = new byte[jsonLength];
			buffer.get(json);
			payload = Json.parse(json);
			int crateLength = buffer.getInt();
			if (crateLength < 0 || crateLength > buffer.remaining()) {
				throw cargoError(400, "Invalid publish request body");
			}
			crate = new byte[crateLength];
			buffer.get(crate);
		} catch (java.nio.BufferUnderflowException | NegativeArraySizeException | JacksonException e) {
			throw cargoError(400, "Invalid publish request body");
		}
		
		String name = Json.text(payload, "name");
		String version = Json.text(payload, "vers");
		if (name == null || !CRATE_NAME.matcher(name).matches()) {
			throw cargoError(400, "Invalid crate name: " + name);
		}
		if (version == null || version.isBlank()) {
			throw cargoError(400, "Missing crate version");
		}
		String key = name.toLowerCase(Locale.ROOT);
		List<PackageVersionEntity> existing = this.services.packages().versions(repository.name(), key);
		for (PackageVersionEntity entity : existing) {
			if (stripBuild(entity.version()).equals(stripBuild(version))) {
				throw cargoError(409, "crate version `" + version + "` is already uploaded");
			}
		}
		if (!existing.isEmpty()) {
			String existingName = Json.text(Json.parseObject(existing.getFirst().metadata()), "name", name);
			if (!existingName.equals(name)) {
				throw cargoError(400, "crate was previously named `" + existingName + "`");
			}
		}
		
		StoredBlob blob = this.services.artifacts().upload(crate);
		ObjectNode line = indexLine(payload, name, version, blob.sha256());
		this.services.artifacts().put(repository.name(), cratePath(key, version), blob, "application/octet-stream", key, version, null, username(principal));
		this.services.packages().create(repository.name(), key, version, Json.write(line), username(principal));
		
		ObjectNode root = Json.object();
		ObjectNode warnings = root.putObject("warnings");
		warnings.putArray("invalid_categories");
		warnings.putArray("invalid_badges");
		warnings.putArray("other");
		json(ctx, 200, root);
	}
	
	/**
	 * Translates the publish metadata into an index line.<br>
	 */
	private static @NonNull ObjectNode indexLine(@NonNull JsonNode payload, @NonNull String name, @NonNull String version, @NonNull String checksum) {
		ObjectNode line = Json.object();
		line.put("name", name);
		line.put("vers", version);
		ArrayNode deps = line.putArray("deps");
		JsonNode payloadDeps = payload.get("deps");
		if (payloadDeps != null && payloadDeps.isArray()) {
			for (JsonNode dep : payloadDeps) {
				ObjectNode node = deps.addObject();
				String depName = Json.text(dep, "name", "");
				String explicitName = Json.text(dep, "explicit_name_in_toml");
				node.put("name", explicitName != null ? explicitName : depName);
				node.put("req", Json.text(dep, "version_req", "*"));
				node.set("features", dep.has("features") ? dep.get("features") : Json.array());
				node.put("optional", dep.path("optional").asBoolean(false));
				node.put("default_features", dep.path("default_features").asBoolean(true));
				String target = Json.text(dep, "target");
				if (target == null) {
					node.putNull("target");
				} else {
					node.put("target", target);
				}
				node.put("kind", Json.text(dep, "kind", "normal"));
				String registry = Json.text(dep, "registry");
				if (registry != null) {
					node.put("registry", registry);
				}
				if (explicitName != null) {
					node.put("package", depName);
				}
			}
		}
		line.put("cksum", checksum);
		
		ObjectNode features = line.putObject("features");
		ObjectNode features2 = Json.object();
		JsonNode payloadFeatures = payload.get("features");
		if (payloadFeatures != null && payloadFeatures.isObject()) {
			for (Map.Entry<String, JsonNode> entry : payloadFeatures.properties()) {
				boolean extended = false;
				for (JsonNode value : entry.getValue()) {
					String text = value.asString();
					if (text.startsWith("dep:") || text.contains("?/")) {
						extended = true;
					}
				}
				(extended ? features2 : features).set(entry.getKey(), entry.getValue());
			}
		}
		if (!features2.isEmpty()) {
			line.set("features2", features2);
			line.put("v", 2);
		}
		line.put("yanked", false);
		String links = Json.text(payload, "links");
		if (links != null) {
			line.put("links", links);
		}
		String rustVersion = Json.text(payload, "rust_version");
		if (rustVersion != null) {
			line.put("rust_version", rustVersion);
		}
		ObjectNode publish = Json.object();
		for (String field : List.of("description", "license", "repository", "homepage", "documentation")) {
			String value = Json.text(payload, field);
			if (value != null) {
				publish.put(field, value);
			}
		}
		if (!publish.isEmpty()) {
			line.set("_publish", publish);
		}
		return line;
	}
	
	private void yank(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String crate, @NonNull String version, boolean yanked) throws Exception {
		this.authorize(ctx, repository, AccessLevel.WRITE);
		if (!this.services.packages().setYanked(repository.name(), crate, version, yanked)) {
			throw cargoError(404, "No such package or version");
		}
		ObjectNode root = Json.object();
		root.put("ok", true);
		json(ctx, 200, root);
	}
	
	private void owners(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String crate, @NonNull String method) throws Exception {
		this.authorize(ctx, repository, "GET".equals(method) ? AccessLevel.READ : AccessLevel.WRITE);
		List<PackageVersionEntity> versions = this.services.packages().versions(repository.name(), crate);
		if (versions.isEmpty()) {
			throw cargoError(404, "No such package");
		}
		ObjectNode root = Json.object();
		if ("GET".equals(method)) {
			ArrayNode users = root.putArray("users");
			Set<String> owners = new TreeSet<>();
			versions.stream().map(PackageVersionEntity::createdBy).filter(Objects::nonNull).forEach(owners::add);
			int id = 1;
			for (String owner : owners) {
				users.addObject().put("id", id++).put("login", owner).put("name", owner);
			}
		} else {
			root.put("ok", true);
			root.put("msg", "owners are managed with repository permissions");
		}
		json(ctx, 200, root);
	}
	//endregion
	
	private static @NonNull String cratePath(@NonNull String crate, @NonNull String version) {
		return "crates/" + crate + "/" + crate + "-" + version + ".crate";
	}
	
	private static @NonNull String stripBuild(@NonNull String version) {
		int plus = version.indexOf('+');
		return plus < 0 ? version : version.substring(0, plus);
	}
	
	private static @NonNull HttpError cargoError(int status, @NonNull String message) {
		ObjectNode root = Json.object();
		root.putArray("errors").addObject().put("detail", message);
		return HttpError.json(status, message, Json.write(root));
	}
}
