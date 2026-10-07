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
import net.luis.artifactory.util.Versions;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.*;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * NuGet V3 feed used by dotnet, nuget.exe and Visual Studio.<br>
 * Source url: {@code {base}/nuget/{repo}/v3/index.json}<br>
 */
public class NugetHandler extends FormatHandler {

	private static final int MAX_NUSPEC_SIZE = 16 * 1024 * 1024;
	private static final Pattern PACKAGE_ID = Pattern.compile("\\w+(?:[.\\-_]\\w+)*");

	public NugetHandler(@NonNull Services services) {
		super(services);
	}
	
	@Override
	public @NonNull RepositoryType type() {
		return RepositoryType.NUGET;
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = Requests.decodedSegments(rawPath);
		String method = Requests.method(ctx);
		boolean read = "GET".equals(method) || "HEAD".equals(method);
		
		if (segments.size() >= 2 && (segments.subList(0, 2).equals(List.of("v3", "package")) || (segments.size() >= 3 && segments.subList(0, 3).equals(List.of("api", "v2", "package"))))) {
			List<String> rest = segments.subList(segments.getFirst().equals("v3") ? 2 : 3, segments.size());
			if (rest.isEmpty() && "PUT".equals(method)) {
				this.push(ctx, repository);
			} else if (rest.size() == 2 && "DELETE".equals(method)) {
				this.setListed(ctx, repository, rest.get(0), rest.get(1), false);
			} else if (rest.size() == 2 && "POST".equals(method)) {
				this.setListed(ctx, repository, rest.get(0), rest.get(1), true);
			} else {
				throw HttpError.methodNotAllowed();
			}
			return;
		}
		if (!read) {
			throw HttpError.methodNotAllowed();
		}
		if (segments.equals(List.of("v3", "index.json")) || segments.equals(List.of("index.json"))) {
			// The service index only contains urls, nuget clients fetch it without credentials before pushing with an api key
			this.serviceIndex(ctx, repository);
			return;
		}
		this.authorize(ctx, repository, AccessLevel.READ);
		
		if (segments.size() == 3 && segments.getFirst().equals("v3-flatcontainer") && segments.get(2).equals("index.json")) {
			this.versionList(ctx, repository, segments.get(1).toLowerCase(Locale.ROOT));
		} else if (segments.size() == 4 && segments.getFirst().equals("v3-flatcontainer")) {
			this.flatFile(ctx, repository, segments.get(1).toLowerCase(Locale.ROOT), segments.get(2).toLowerCase(Locale.ROOT), segments.get(3).toLowerCase(Locale.ROOT));
		} else if (segments.size() == 4 && segments.subList(0, 2).equals(List.of("v3", "registration"))) {
			String id = segments.get(2).toLowerCase(Locale.ROOT);
			String file = segments.get(3).toLowerCase(Locale.ROOT);
			if (file.equals("index.json")) {
				this.registrationIndex(ctx, repository, id);
			} else if (file.endsWith(".json")) {
				this.registrationLeaf(ctx, repository, id, file.substring(0, file.length() - 5));
			} else {
				throw HttpError.notFound();
			}
		} else if (segments.equals(List.of("v3", "query"))) {
			this.search(ctx, repository);
		} else if (segments.equals(List.of("v3", "autocomplete"))) {
			this.autocomplete(ctx, repository);
		} else {
			throw HttpError.notFound();
		}
	}
	
	//region Read
	private void serviceIndex(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		String base = this.repositoryUrl(ctx, repository);
		ObjectNode root = Json.object();
		root.put("version", "3.0.0");
		ArrayNode resources = root.putArray("resources");
		addResource(resources, base + "/v3-flatcontainer/", "PackageBaseAddress/3.0.0");
		for (String type : List.of("RegistrationsBaseUrl", "RegistrationsBaseUrl/3.0.0-rc", "RegistrationsBaseUrl/3.0.0-beta", "RegistrationsBaseUrl/3.4.0", "RegistrationsBaseUrl/3.6.0")) {
			addResource(resources, base + "/v3/registration/", type);
		}
		for (String type : List.of("SearchQueryService", "SearchQueryService/3.0.0-rc", "SearchQueryService/3.0.0-beta", "SearchQueryService/3.5.0")) {
			addResource(resources, base + "/v3/query", type);
		}
		for (String type : List.of("SearchAutocompleteService", "SearchAutocompleteService/3.0.0-rc", "SearchAutocompleteService/3.0.0-beta")) {
			addResource(resources, base + "/v3/autocomplete", type);
		}
		addResource(resources, base + "/v3/package", "PackagePublish/2.0.0");
		json(ctx, 200, root);
	}
	
	private static void addResource(@NonNull ArrayNode resources, @NonNull String id, @NonNull String type) {
		resources.addObject().put("@id", id).put("@type", type);
	}
	
	private @NonNull List<PackageVersionEntity> sortedVersions(@NonNull RepositoryEntity repository, @NonNull String lowerId) throws Exception {
		List<PackageVersionEntity> versions = new ArrayList<>(this.services.packages().versions(repository.name(), lowerId));
		versions.sort(Comparator.comparing(PackageVersionEntity::version, Versions.SEMVER));
		return versions;
	}
	
	private void versionList(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String lowerId) throws Exception {
		List<PackageVersionEntity> versions = this.sortedVersions(repository, lowerId);
		if (versions.isEmpty()) {
			throw HttpError.notFound();
		}
		ObjectNode root = Json.object();
		ArrayNode array = root.putArray("versions");
		versions.forEach(version -> array.add(version.version()));
		json(ctx, 200, root);
	}
	
	private void flatFile(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String lowerId, @NonNull String version, @NonNull String file) throws Exception {
		String lowerVersion = normalize(version).toLowerCase(Locale.ROOT);
		if (file.endsWith(".nuspec")) {
			PackageVersionEntity entity = this.services.packages().get(repository.name(), lowerId, lowerVersion);
			if (entity == null) {
				throw HttpError.notFound();
			}
			ctx.status(200).contentType("application/xml").result(Json.text(Json.parseObject(entity.metadata()), "nuspec", ""));
			return;
		}
		FileEntity entity = this.services.artifacts().get(repository.name(), lowerId + "/" + lowerVersion + "/" + file);
		if (entity == null) {
			throw HttpError.notFound();
		}
		this.services.artifacts().serve(ctx, entity, "application/octet-stream");
	}
	
	private void registrationIndex(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String lowerId) throws Exception {
		List<PackageVersionEntity> versions = this.sortedVersions(repository, lowerId);
		if (versions.isEmpty()) {
			throw HttpError.notFound();
		}
		String base = this.repositoryUrl(ctx, repository);
		String indexUrl = base + "/v3/registration/" + lowerId + "/index.json";
		
		ObjectNode root = Json.object();
		root.put("@id", indexUrl);
		root.put("count", 1);
		ObjectNode page = root.putArray("items").addObject();
		page.put("@id", indexUrl + "#page/" + versions.getFirst().version() + "/" + versions.getLast().version());
		page.put("count", versions.size());
		page.put("lower", versions.getFirst().version());
		page.put("upper", versions.getLast().version());
		page.put("parent", indexUrl);
		ArrayNode items = page.putArray("items");
		for (PackageVersionEntity version : versions) {
			items.add(this.registrationLeafNode(base, lowerId, version));
		}
		json(ctx, 200, root);
	}
	
	private void registrationLeaf(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String lowerId, @NonNull String version) throws Exception {
		PackageVersionEntity entity = this.services.packages().get(repository.name(), lowerId, normalize(version).toLowerCase(Locale.ROOT));
		if (entity == null) {
			throw HttpError.notFound();
		}
		ObjectNode leaf = this.registrationLeafNode(this.repositoryUrl(ctx, repository), lowerId, entity);
		leaf.put("listed", Json.parseObject(entity.metadata()).path("listed").asBoolean(true));
		json(ctx, 200, leaf);
	}
	
	private @NonNull ObjectNode registrationLeafNode(@NonNull String base, @NonNull String lowerId, @NonNull PackageVersionEntity version) {
		ObjectNode metadata = Json.parseObject(version.metadata());
		String leafUrl = base + "/v3/registration/" + lowerId + "/" + version.version() + ".json";
		String content = base + "/v3-flatcontainer/" + lowerId + "/" + version.version() + "/" + lowerId + "." + version.version() + ".nupkg";
		
		ObjectNode leaf = Json.object();
		leaf.put("@id", leafUrl);
		leaf.put("@type", "Package");
		ObjectNode entry = leaf.putObject("catalogEntry");
		entry.put("@id", leafUrl + "#catalogEntry");
		entry.put("@type", "PackageDetails");
		entry.put("id", Json.text(metadata, "id", lowerId));
		entry.put("version", Json.text(metadata, "version", version.version()));
		entry.put("description", Json.text(metadata, "description", ""));
		entry.put("authors", Json.text(metadata, "authors", ""));
		entry.put("title", Json.text(metadata, "title", ""));
		entry.put("summary", Json.text(metadata, "summary", ""));
		entry.put("projectUrl", Json.text(metadata, "projectUrl", ""));
		entry.put("licenseExpression", Json.text(metadata, "licenseExpression", ""));
		entry.put("listed", metadata.path("listed").asBoolean(true));
		entry.put("published", DateTimeFormatter.ISO_INSTANT.format(version.createdAt()));
		entry.put("packageContent", content);
		if (metadata.has("tags")) {
			entry.set("tags", metadata.get("tags"));
		}
		entry.set("dependencyGroups", metadata.has("dependencyGroups") ? metadata.get("dependencyGroups") : Json.array());
		leaf.put("packageContent", content);
		leaf.put("registration", base + "/v3/registration/" + lowerId + "/index.json");
		return leaf;
	}
	
	private void search(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		String query = Objects.requireNonNullElse(ctx.queryParam("q"), "").toLowerCase(Locale.ROOT).strip();
		int skip = parseInt(ctx.queryParam("skip"), 0);
		int take = parseInt(ctx.queryParam("take"), 20);
		boolean prerelease = Boolean.parseBoolean(ctx.queryParam("prerelease"));
		String base = this.repositoryUrl(ctx, repository);
		
		List<ObjectNode> results = new ArrayList<>();
		for (Map.Entry<String, List<PackageVersionEntity>> entry : this.services.packages().grouped(repository.name()).entrySet()) {
			String lowerId = entry.getKey();
			List<PackageVersionEntity> versions = entry.getValue().stream()
				.filter(version -> Json.parseObject(version.metadata()).path("listed").asBoolean(true))
				.filter(version -> prerelease || !Versions.isPrerelease(version.version()))
				.sorted(Comparator.comparing(PackageVersionEntity::version, Versions.SEMVER))
				.toList();
			if (versions.isEmpty()) {
				continue;
			}
			ObjectNode latest = Json.parseObject(versions.getLast().metadata());
			String id = Json.text(latest, "id", lowerId);
			String description = Json.text(latest, "description", "");
			if (!query.isEmpty() && !id.toLowerCase(Locale.ROOT).contains(query) && !description.toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}
			
			ObjectNode result = Json.object();
			result.put("@id", base + "/v3/registration/" + lowerId + "/index.json");
			result.put("@type", "Package");
			result.put("registration", base + "/v3/registration/" + lowerId + "/index.json");
			result.put("id", id);
			result.put("version", Json.text(latest, "version", versions.getLast().version()));
			result.put("description", description);
			result.put("summary", Json.text(latest, "summary", ""));
			result.put("title", Json.text(latest, "title", id));
			result.putArray("authors").add(Json.text(latest, "authors", ""));
			result.put("totalDownloads", 0);
			result.put("verified", false);
			result.putArray("packageTypes").addObject().put("name", "Dependency");
			ArrayNode versionArray = result.putArray("versions");
			for (PackageVersionEntity version : versions) {
				versionArray.addObject()
					.put("version", Json.text(Json.parseObject(version.metadata()), "version", version.version()))
					.put("downloads", 0)
					.put("@id", base + "/v3/registration/" + lowerId + "/" + version.version() + ".json");
			}
			results.add(result);
		}
		
		ObjectNode root = Json.object();
		root.put("totalHits", results.size());
		ArrayNode data = root.putArray("data");
		results.stream().skip(skip).limit(take).forEach(data::add);
		json(ctx, 200, root);
	}
	
	private void autocomplete(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		String query = Objects.requireNonNullElse(ctx.queryParam("q"), "").toLowerCase(Locale.ROOT);
		String id = ctx.queryParam("id");
		ObjectNode root = Json.object();
		ArrayNode data = root.putArray("data");
		if (id != null) {
			this.sortedVersions(repository, id.toLowerCase(Locale.ROOT)).forEach(version -> data.add(version.version()));
		} else {
			for (Map.Entry<String, List<PackageVersionEntity>> entry : this.services.packages().grouped(repository.name()).entrySet()) {
				String packageId = Json.text(Json.parseObject(entry.getValue().getLast().metadata()), "id", entry.getKey());
				if (packageId.toLowerCase(Locale.ROOT).contains(query)) {
					data.add(packageId);
				}
			}
		}
		root.put("totalHits", data.size());
		json(ctx, 200, root);
	}
	//endregion
	
	//region Write
	private void push(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		Principal principal = this.authorize(ctx, repository, AccessLevel.WRITE);
		
		StoredBlob blob;
		if (ctx.isMultipartFormData()) {
			List<UploadedFile> files = ctx.uploadedFiles();
			if (files.isEmpty()) {
				throw HttpError.badRequest("Missing package file");
			}
			try (InputStream input = files.getFirst().content()) {
				blob = this.services.artifacts().upload(input);
			}
		} else {
			try (InputStream input = ctx.bodyInputStream()) {
				blob = this.services.artifacts().upload(input);
			}
		}
		
		Nuspec nuspec;
		try {
			nuspec = readNuspec(this.services.artifacts().blobStore().path(blob.sha256()).toFile());
		} catch (Exception e) {
			this.services.artifacts().releaseBlobs(List.of(PathRepositoryHandler.fakeFile(repository, blob)));
			throw HttpError.badRequest("Invalid package: " + e.getMessage());
		}
		
		String lowerId = nuspec.id().toLowerCase(Locale.ROOT);
		String version = normalize(nuspec.version());
		String lowerVersion = version.toLowerCase(Locale.ROOT);
		if (this.services.packages().get(repository.name(), lowerId, lowerVersion) != null) {
			if (!repository.allowRedeploy()) {
				this.services.artifacts().releaseBlobs(List.of(PathRepositoryHandler.fakeFile(repository, blob)));
				throw HttpError.conflict("Version " + version + " already exists; releases are immutable");
			}
			this.services.packages().delete(repository.name(), lowerId, lowerVersion);
		}
		
		ObjectNode metadata = nuspec.metadata();
		metadata.put("version", version);
		metadata.put("listed", true);
		String path = lowerId + "/" + lowerVersion + "/" + lowerId + "." + lowerVersion + ".nupkg";
		this.services.artifacts().put(repository.name(), path, blob, "application/octet-stream", lowerId, lowerVersion, null, username(principal));
		this.services.packages().create(repository.name(), lowerId, lowerVersion, Json.write(metadata), username(principal));
		ctx.status(201);
	}
	
	private void setListed(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String id, @NonNull String version, boolean listed) throws Exception {
		this.authorize(ctx, repository, AccessLevel.WRITE);
		String lowerId = id.toLowerCase(Locale.ROOT);
		String lowerVersion = normalize(version).toLowerCase(Locale.ROOT);
		PackageVersionEntity entity = this.services.packages().get(repository.name(), lowerId, lowerVersion);
		if (entity == null) {
			throw HttpError.notFound();
		}
		ObjectNode metadata = Json.parseObject(entity.metadata());
		metadata.put("listed", listed);
		this.services.packages().updateMetadata(repository.name(), lowerId, lowerVersion, Json.write(metadata));
		this.services.packages().setYanked(repository.name(), lowerId, lowerVersion, !listed);
		ctx.status(listed ? 200 : 204);
	}
	//endregion
	
	//region Nuspec
	private record Nuspec(@NonNull String id, @NonNull String version, @NonNull ObjectNode metadata) {}
	
	private static @NonNull Nuspec readNuspec(@NonNull File file) throws Exception {
		try (ZipFile zip = new ZipFile(file)) {
			ZipEntry nuspecEntry = null;
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().contains("/") && entry.getName().toLowerCase(Locale.ROOT).endsWith(".nuspec")) {
					nuspecEntry = entry;
					break;
				}
			}
			if (nuspecEntry == null) {
				throw new IllegalArgumentException("No .nuspec file found in package");
			}
			if (nuspecEntry.getSize() > MAX_NUSPEC_SIZE) {
				throw new IllegalArgumentException(".nuspec file is too large");
			}
			byte[] data;
			try (InputStream input = zip.getInputStream(nuspecEntry)) {
				// Bound the read, the declared size can not be trusted and the entry may be a decompression bomb.
				data = input.readNBytes(MAX_NUSPEC_SIZE + 1);
			}
			if (data.length > MAX_NUSPEC_SIZE) {
				throw new IllegalArgumentException(".nuspec file is too large");
			}
			
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
			Element metadataElement = child(document.getDocumentElement(), "metadata");
			if (metadataElement == null) {
				throw new IllegalArgumentException("Missing metadata element in .nuspec");
			}
			
			String id = childText(metadataElement, "id");
			String version = childText(metadataElement, "version");
			if (id == null || version == null) {
				throw new IllegalArgumentException("Missing id or version in .nuspec");
			}
			if (!PACKAGE_ID.matcher(id).matches()) {
				throw new IllegalArgumentException("Invalid package id: " + id);
			}
			
			ObjectNode metadata = Json.object();
			metadata.put("id", id);
			metadata.put("nuspec", new String(data, StandardCharsets.UTF_8));
			for (String field : List.of("description", "authors", "title", "summary", "projectUrl")) {
				String value = childText(metadataElement, field);
				if (value != null) {
					metadata.put(field, value);
				}
			}
			Element license = child(metadataElement, "license");
			if (license != null && "expression".equals(license.getAttribute("type"))) {
				metadata.put("licenseExpression", license.getTextContent().strip());
			}
			String tags = childText(metadataElement, "tags");
			if (tags != null) {
				ArrayNode array = metadata.putArray("tags");
				Arrays.stream(tags.split("[\\s,;]+")).filter(tag -> !tag.isBlank()).forEach(array::add);
			}
			metadata.set("dependencyGroups", dependencyGroups(child(metadataElement, "dependencies")));
			return new Nuspec(id, version, metadata);
		}
	}
	
	private static @NonNull ArrayNode dependencyGroups(@Nullable Element dependencies) {
		ArrayNode groups = Json.array();
		if (dependencies == null) {
			return groups;
		}
		List<Element> groupElements = children(dependencies, "group");
		if (groupElements.isEmpty()) {
			ObjectNode group = groups.addObject();
			group.put("@type", "PackageDependencyGroup");
			addDependencies(group, dependencies);
			return groups;
		}
		for (Element groupElement : groupElements) {
			ObjectNode group = groups.addObject();
			group.put("@type", "PackageDependencyGroup");
			if (groupElement.hasAttribute("targetFramework")) {
				group.put("targetFramework", groupElement.getAttribute("targetFramework"));
			}
			addDependencies(group, groupElement);
		}
		return groups;
	}
	
	private static void addDependencies(@NonNull ObjectNode group, @NonNull Element parent) {
		ArrayNode array = group.putArray("dependencies");
		for (Element dependency : children(parent, "dependency")) {
			ObjectNode node = array.addObject();
			node.put("@type", "PackageDependency");
			node.put("id", dependency.getAttribute("id"));
			String range = dependency.getAttribute("version");
			node.put("range", range.isBlank() ? "(, )" : (range.startsWith("[") || range.startsWith("(") ? range : "[" + range + ", )"));
		}
	}
	
	private static @NonNull List<Element> children(@NonNull Element parent, @NonNull String name) {
		List<Element> result = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element element && name.equals(element.getLocalName() == null ? element.getNodeName() : element.getLocalName())) {
				result.add(element);
			}
		}
		return result;
	}
	
	private static @Nullable Element child(@NonNull Element parent, @NonNull String name) {
		List<Element> elements = children(parent, name);
		return elements.isEmpty() ? null : elements.getFirst();
	}
	
	private static @Nullable String childText(@NonNull Element parent, @NonNull String name) {
		Element element = child(parent, name);
		if (element == null) {
			return null;
		}
		String text = element.getTextContent().strip();
		return text.isEmpty() ? null : text;
	}
	//endregion
	
	private static @NonNull String normalize(@NonNull String version) {
		try {
			return Versions.normalizeNuGet(version);
		} catch (IllegalArgumentException e) {
			throw HttpError.badRequest(e.getMessage());
		}
	}
	
	private static int parseInt(@Nullable String value, int defaultValue) {
		try {
			return value == null ? defaultValue : Math.max(0, Integer.parseInt(value));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
