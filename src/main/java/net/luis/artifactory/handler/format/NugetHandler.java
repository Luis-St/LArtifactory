package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
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
import net.luis.artifactory.util.Versions;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.*;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
	
	private static final int MAX_NUSPEC_SIZE = 8 * 1024 * 1024;
	private static final int MAX_TAKE = 1000;
	private static final int SEARCH_BATCH_SIZE = 100;
	private static final Pattern PACKAGE_ID = Pattern.compile("\\w+(?:[.\\-_]\\w+)*");
	private static final Pattern PACKAGE_TYPE = Pattern.compile("<(?:\\w+:)?packageType\\b[^>]*\\bname\\s*=\\s*\"([^\"]+)\"");
	/**
	 * Registration hive without SemVer 2.0.0 packages, served for the unversioned, {@code 3.0.0-rc}, {@code 3.0.0-beta} and {@code 3.4.0} types.<br>
	 */
	private static final String REGISTRATION = "registration";
	/**
	 * Registration hive with all packages, served for the {@code 3.6.0} type.<br>
	 */
	private static final String REGISTRATION_SEMVER2 = "registration-semver2";
	
	public NugetHandler(@NonNull Services services) {
		super(services);
	}
	
	private static void addResource(@NonNull ArrayNode resources, @NonNull String id, @NonNull String type) {
		resources.addObject().put("@id", id).put("@type", type);
	}
	
	@NonNull
	private static Nuspec readNuspec(@NonNull File file) throws Exception {
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
				// Bound the decompressed size, a maliciously crafted (highly compressed) .nuspec entry could otherwise
				// exhaust memory. The zip entry's declared size can be spoofed, so enforce the limit on the actual read.
				data = input.readNBytes(MAX_NUSPEC_SIZE + 1);
				if (data.length > MAX_NUSPEC_SIZE) {
					throw new IllegalArgumentException(".nuspec file exceeds the maximum supported size");
				}
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
			// Build metadata is dropped by the normalization, but makes the package a SemVer 2.0.0 package
			metadata.put("semVer2", isSemVer2(version));
			ArrayNode packageTypes = metadata.putArray("packageTypes");
			for (Element packageType : children(child(metadataElement, "packageTypes"), "packageType")) {
				String name = packageType.getAttribute("name").strip();
				if (!name.isEmpty()) {
					packageTypes.add(name);
				}
			}
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
	
	@NonNull
	private static ArrayNode dependencyGroups(@Nullable Element dependencies) {
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
	
	@NonNull
	private static List<Element> children(@Nullable Element parent, @NonNull String name) {
		List<Element> result = new ArrayList<>();
		if (parent == null) {
			return result;
		}
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			if (nodes.item(i) instanceof Element element && name.equals(element.getLocalName() == null ? element.getNodeName() : element.getLocalName())) {
				result.add(element);
			}
		}
		return result;
	}
	
	@Nullable
	private static Element child(@Nullable Element parent, @NonNull String name) {
		List<Element> elements = children(parent, name);
		return elements.isEmpty() ? null : elements.getFirst();
	}
	
	@Nullable
	private static String childText(@NonNull Element parent, @NonNull String name) {
		Element element = child(parent, name);
		if (element == null) {
			return null;
		}
		String text = element.getTextContent().strip();
		return text.isEmpty() ? null : text;
	}
	
	@NonNull
	private static String normalize(@NonNull String version) {
		try {
			return Versions.normalizeNuGet(version);
		} catch (IllegalArgumentException e) {
			throw HttpError.badRequest(e.getMessage());
		}
	}
	
	/**
	 * A SemVer 2.0.0 version has a dotted prerelease label or build metadata, clients before NuGet 4.3 can not handle them.<br>
	 */
	private static boolean isSemVer2(@NonNull String version) {
		int dash = version.indexOf('-');
		int plus = version.indexOf('+');
		String prerelease = dash < 0 ? "" : version.substring(dash + 1, plus > dash ? plus : version.length());
		return plus >= 0 || prerelease.contains(".");
	}
	
	private static boolean isSemVer2(@NonNull PackageVersionEntity version) {
		return isSemVer2(version.version()) || Json.parseObject(version.metadata()).path("semVer2").asBoolean(false);
	}
	
	/**
	 * Whether a query asks for SemVer 2.0.0 packages with {@code semVerLevel=2.0.0}, they are hidden from older clients.<br>
	 */
	private static boolean includesSemVer2(@NonNull Context ctx) {
		String level = ctx.queryParam("semVerLevel");
		return level != null && Versions.SEMVER.compare(level.strip(), "2.0.0") >= 0;
	}
	
	private static boolean isListed(@NonNull PackageVersionEntity version) {
		return Json.parseObject(version.metadata()).path("listed").asBoolean(true);
	}
	
	/**
	 * Returns the package types of a version, {@code Dependency} if the nuspec declares none.<br>
	 */
	@NonNull
	private static List<String> packageTypes(@NonNull ObjectNode metadata) {
		List<String> types = new ArrayList<>();
		if (metadata.has("packageTypes")) {
			metadata.path("packageTypes").forEach(type -> types.add(type.asString()));
		} else {
			// Packages pushed before the types were recorded
			var matcher = PACKAGE_TYPE.matcher(Json.text(metadata, "nuspec", ""));
			while (matcher.find()) {
				types.add(matcher.group(1));
			}
		}
		return types.isEmpty() ? List.of("Dependency") : types;
	}
	
	private static int parseInt(@Nullable String value, int defaultValue) {
		try {
			return value == null ? defaultValue : Math.max(0, Integer.parseInt(value));
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
	
	@NonNull
	private static String catalogUrl(@NonNull String base, @NonNull String lowerId, @NonNull PackageVersionEntity version) {
		return base + "/v3/catalog/" + lowerId + "/" + version.version() + ".json";
	}
	
	@NonNull
	private static String packageContent(@NonNull String base, @NonNull String lowerId, @NonNull PackageVersionEntity version) {
		return base + "/v3-flatcontainer/" + lowerId + "/" + version.version() + "/" + lowerId + "." + version.version() + ".nupkg";
	}
	
	@Override
	@NonNull
	public RepositoryType type() {
		return RepositoryType.NUGET;
	}
	
	@Override
	public void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String rawPath) throws Exception {
		List<String> segments = Requests.decodedSegments(rawPath);
		String method = Requests.method(ctx);
		boolean read = "GET".equals(method) || "HEAD".equals(method);
		
		if (segments.size() >= 2 && (segments.subList(0, 2).equals(List.of("v3", "package")) || (segments.size() >= 3 && segments.subList(0, 3).equals(List.of("api", "v2", "package"))))) {
			List<String> rest = segments.subList("v3".equals(segments.getFirst()) ? 2 : 3, segments.size());
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
		
		if (segments.size() == 3 && "v3-flatcontainer".equals(segments.getFirst()) && "index.json".equals(segments.get(2))) {
			this.versionList(ctx, repository, segments.get(1).toLowerCase(Locale.ROOT));
		} else if (segments.size() == 4 && "v3-flatcontainer".equals(segments.getFirst())) {
			this.flatFile(ctx, repository, segments.get(1).toLowerCase(Locale.ROOT), segments.get(2).toLowerCase(Locale.ROOT), segments.get(3).toLowerCase(Locale.ROOT));
		} else if (segments.size() == 4 && "v3".equals(segments.getFirst()) && List.of(REGISTRATION, REGISTRATION_SEMVER2).contains(segments.get(1))) {
			boolean semVer2 = REGISTRATION_SEMVER2.equals(segments.get(1));
			String id = segments.get(2).toLowerCase(Locale.ROOT);
			String file = segments.get(3).toLowerCase(Locale.ROOT);
			if ("index.json".equals(file)) {
				this.registrationIndex(ctx, repository, id, semVer2);
			} else if (file.endsWith(".json")) {
				this.registrationLeaf(ctx, repository, id, file.substring(0, file.length() - 5), semVer2);
			} else {
				throw HttpError.notFound();
			}
		} else if (segments.size() == 4 && segments.subList(0, 2).equals(List.of("v3", "catalog")) && segments.get(3).toLowerCase(Locale.ROOT).endsWith(".json")) {
			String file = segments.get(3).toLowerCase(Locale.ROOT);
			this.catalogEntry(ctx, repository, segments.get(2).toLowerCase(Locale.ROOT), file.substring(0, file.length() - 5));
		} else if (segments.equals(List.of("v3", "query"))) {
			this.search(ctx, repository);
		} else if (segments.equals(List.of("v3", "autocomplete"))) {
			this.autocomplete(ctx, repository);
		} else {
			throw HttpError.notFound();
		}
	}
	
	private void serviceIndex(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		String base = this.repositoryUrl(ctx, repository);
		ObjectNode root = Json.object();
		root.put("version", "3.0.0");
		ArrayNode resources = root.putArray("resources");
		addResource(resources, base + "/v3-flatcontainer/", "PackageBaseAddress/3.0.0");
		for (String type : List.of("RegistrationsBaseUrl", "RegistrationsBaseUrl/3.0.0-rc", "RegistrationsBaseUrl/3.0.0-beta", "RegistrationsBaseUrl/3.4.0")) {
			addResource(resources, base + "/v3/" + REGISTRATION + "/", type);
		}
		addResource(resources, base + "/v3/" + REGISTRATION_SEMVER2 + "/", "RegistrationsBaseUrl/3.6.0");
		for (String type : List.of("SearchQueryService", "SearchQueryService/3.0.0-rc", "SearchQueryService/3.0.0-beta", "SearchQueryService/3.5.0")) {
			addResource(resources, base + "/v3/query", type);
		}
		for (String type : List.of("SearchAutocompleteService", "SearchAutocompleteService/3.0.0-rc", "SearchAutocompleteService/3.0.0-beta")) {
			addResource(resources, base + "/v3/autocomplete", type);
		}
		addResource(resources, base + "/v3/package", "PackagePublish/2.0.0");
		json(ctx, 200, root);
	}
	
	@NonNull
	private List<PackageVersionEntity> sortedVersions(@NonNull RepositoryEntity repository, @NonNull String lowerId) throws Exception {
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
			// The nuspec is uploaded content, serve it as a download so a browser never renders it (xml may embed xhtml script)
			ctx.header("Content-Disposition", "attachment; filename=\"" + lowerId + ".nuspec\"");
			ctx.status(200).contentType("application/xml").result(Json.text(Json.parseObject(entity.metadata()), "nuspec", ""));
			return;
		}
		FileEntity entity = this.services.artifacts().get(repository.name(), lowerId + "/" + lowerVersion + "/" + file);
		if (entity == null) {
			throw HttpError.notFound();
		}
		this.services.artifacts().serve(ctx, entity, "application/octet-stream");
	}
	
	private void registrationIndex(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String lowerId, boolean semVer2) throws Exception {
		List<PackageVersionEntity> versions = this.sortedVersions(repository, lowerId).stream().filter(version -> semVer2 || !isSemVer2(version)).toList();
		if (versions.isEmpty()) {
			throw HttpError.notFound();
		}
		String base = this.repositoryUrl(ctx, repository);
		String hive = base + "/v3/" + (semVer2 ? REGISTRATION_SEMVER2 : REGISTRATION) + "/";
		String indexUrl = hive + lowerId + "/index.json";
		
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
			ObjectNode leaf = items.addObject();
			leaf.put("@id", hive + lowerId + "/" + version.version() + ".json");
			leaf.put("@type", "Package");
			// A registration page embeds the catalog entry, the leaf only links it
			leaf.set("catalogEntry", this.catalogEntryNode(base, lowerId, version));
			leaf.put("packageContent", packageContent(base, lowerId, version));
			leaf.put("registration", indexUrl);
		}
		json(ctx, 200, root);
	}
	
	private void registrationLeaf(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String lowerId, @NonNull String version, boolean semVer2) throws Exception {
		PackageVersionEntity entity = this.services.packages().get(repository.name(), lowerId, normalize(version).toLowerCase(Locale.ROOT));
		if (entity == null || (!semVer2 && isSemVer2(entity))) {
			throw HttpError.notFound();
		}
		String base = this.repositoryUrl(ctx, repository);
		String hive = base + "/v3/" + (semVer2 ? REGISTRATION_SEMVER2 : REGISTRATION) + "/";
		ObjectNode leaf = Json.object();
		leaf.put("@id", hive + lowerId + "/" + entity.version() + ".json");
		leaf.putArray("@type").add("Package").add("http://schema.nuget.org/catalog#Permalink");
		leaf.put("catalogEntry", catalogUrl(base, lowerId, entity));
		leaf.put("listed", isListed(entity));
		leaf.put("packageContent", packageContent(base, lowerId, entity));
		leaf.put("published", DateTimeFormatter.ISO_INSTANT.format(entity.createdAt()));
		leaf.put("registration", hive + lowerId + "/index.json");
		json(ctx, 200, leaf);
	}
	
	private void catalogEntry(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String lowerId, @NonNull String version) throws Exception {
		PackageVersionEntity entity = this.services.packages().get(repository.name(), lowerId, normalize(version).toLowerCase(Locale.ROOT));
		if (entity == null) {
			throw HttpError.notFound();
		}
		json(ctx, 200, this.catalogEntryNode(this.repositoryUrl(ctx, repository), lowerId, entity));
	}
	
	@NonNull
	private ObjectNode catalogEntryNode(@NonNull String base, @NonNull String lowerId, @NonNull PackageVersionEntity version) {
		ObjectNode metadata = Json.parseObject(version.metadata());
		ObjectNode entry = Json.object();
		entry.put("@id", catalogUrl(base, lowerId, version));
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
		entry.put("packageContent", packageContent(base, lowerId, version));
		if (metadata.has("tags")) {
			entry.set("tags", metadata.get("tags"));
		}
		entry.set("dependencyGroups", metadata.has("dependencyGroups") ? metadata.get("dependencyGroups") : Json.array());
		return entry;
	}
	
	private void search(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		String query = Objects.requireNonNullElse(ctx.queryParam("q"), "").toLowerCase(Locale.ROOT).strip();
		int skip = parseInt(ctx.queryParam("skip"), 0);
		int take = Math.min(parseInt(ctx.queryParam("take"), 20), MAX_TAKE);
		boolean prerelease = Boolean.parseBoolean(ctx.queryParam("prerelease"));
		boolean semVer2 = includesSemVer2(ctx);
		String packageType = ctx.queryParam("packageType");
		String base = this.repositoryUrl(ctx, repository);
		String hive = base + "/v3/" + (semVer2 ? REGISTRATION_SEMVER2 : REGISTRATION) + "/";
		
		// Versions are loaded for a batch of packages at a time, only the requested page is kept in memory
		List<ObjectNode> results = new ArrayList<>();
		int totalHits = 0;
		List<String> names = this.services.packages().packageNames(repository.name());
		Map<String, List<PackageVersionEntity>> versionsByName = Map.of();
		for (int index = 0; index < names.size(); index++) {
			if (index % SEARCH_BATCH_SIZE == 0) {
				versionsByName = this.services.packages().versions(repository.name(), names.subList(index, Math.min(names.size(), index + SEARCH_BATCH_SIZE)));
			}
			String lowerId = names.get(index);
			List<PackageVersionEntity> versions = versionsByName.getOrDefault(lowerId, List.of()).stream()
				.filter(NugetHandler::isListed)
				.filter(version -> prerelease || !Versions.isPrerelease(version.version()))
				.filter(version -> semVer2 || !isSemVer2(version))
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
			List<String> packageTypes = packageTypes(latest);
			if (packageType != null && !packageType.isBlank() && packageTypes.stream().noneMatch(packageType.strip()::equalsIgnoreCase)) {
				continue;
			}
			if (totalHits++ < skip || results.size() >= take) {
				continue;
			}
			
			ObjectNode result = Json.object();
			result.put("@id", hive + lowerId + "/index.json");
			result.put("@type", "Package");
			result.put("registration", hive + lowerId + "/index.json");
			result.put("id", id);
			result.put("version", Json.text(latest, "version", versions.getLast().version()));
			result.put("description", description);
			result.put("summary", Json.text(latest, "summary", ""));
			result.put("title", Json.text(latest, "title", id));
			result.putArray("authors").add(Json.text(latest, "authors", ""));
			result.put("totalDownloads", 0);
			result.put("verified", false);
			ArrayNode typeArray = result.putArray("packageTypes");
			packageTypes.forEach(type -> typeArray.addObject().put("name", type));
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
		root.put("totalHits", totalHits);
		ArrayNode data = root.putArray("data");
		results.forEach(data::add);
		json(ctx, 200, root);
	}
	
	/**
	 * Lists package ids or the versions of a package, unlisted versions are never listed and prerelease and SemVer 2.0.0 versions only on request.<br>
	 */
	private void autocomplete(@NonNull Context ctx, @NonNull RepositoryEntity repository) throws Exception {
		String query = Objects.requireNonNullElse(ctx.queryParam("q"), "").toLowerCase(Locale.ROOT);
		String id = ctx.queryParam("id");
		boolean prerelease = Boolean.parseBoolean(ctx.queryParam("prerelease"));
		boolean semVer2 = includesSemVer2(ctx);
		java.util.function.Predicate<PackageVersionEntity> visible = version -> isListed(version)
			&& (prerelease || !Versions.isPrerelease(version.version()))
			&& (semVer2 || !isSemVer2(version));
		
		ObjectNode root = Json.object();
		ArrayNode data = root.putArray("data");
		if (id != null) {
			this.sortedVersions(repository, id.toLowerCase(Locale.ROOT)).stream()
				.filter(visible)
				.forEach(version -> data.add(Json.text(Json.parseObject(version.metadata()), "version", version.version())));
			root.put("totalHits", data.size());
			json(ctx, 200, root);
			return;
		}
		
		int skip = parseInt(ctx.queryParam("skip"), 0);
		int take = Math.min(parseInt(ctx.queryParam("take"), 20), MAX_TAKE);
		List<String> names = this.services.packages().packageNames(repository.name()).stream().filter(name -> name.contains(query)).toList();
		int totalHits = 0;
		for (int index = 0; index < names.size(); index += SEARCH_BATCH_SIZE) {
			Map<String, List<PackageVersionEntity>> versionsByName = this.services.packages().versions(repository.name(), names.subList(index, Math.min(names.size(), index + SEARCH_BATCH_SIZE)));
			for (String lowerId : names.subList(index, Math.min(names.size(), index + SEARCH_BATCH_SIZE))) {
				List<PackageVersionEntity> versions = versionsByName.getOrDefault(lowerId, List.of()).stream().filter(visible).toList();
				if (versions.isEmpty()) {
					continue;
				}
				if (totalHits++ >= skip && data.size() < take) {
					data.add(Json.text(Json.parseObject(versions.getLast().metadata()), "id", lowerId));
				}
			}
		}
		root.put("totalHits", totalHits);
		json(ctx, 200, root);
	}
	
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
		ObjectNode metadata = nuspec.metadata();
		metadata.put("version", version);
		metadata.put("listed", true);
		String path = lowerId + "/" + lowerVersion + "/" + lowerId + "." + lowerVersion + ".nupkg";
		
		try {
			this.services.artifacts().transaction(work -> {
				if (this.services.packages().get(work, repository.name(), lowerId, lowerVersion) != null) {
					if (!repository.allowRedeploy()) {
						throw HttpError.conflict("Version " + version + " already exists; releases are immutable");
					}
					this.services.packages().delete(work, repository.name(), lowerId, lowerVersion);
				}
				this.services.artifacts().put(work, repository.name(), path, blob, "application/octet-stream", lowerId, lowerVersion, null, username(principal));
				return this.services.packages().create(work, repository.name(), lowerId, lowerVersion, Json.write(metadata), username(principal));
			});
		} catch (HttpError | SqlException e) {
			this.services.artifacts().releaseBlobs(List.of(PathRepositoryHandler.fakeFile(repository, blob)));
			if (SqlErrors.isUniqueViolation(e)) {
				throw HttpError.conflict("Version " + version + " was pushed concurrently");
			}
			throw e;
		}
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
		this.services.packages().setYanked(repository.name(), lowerId, lowerVersion, !listed, Json.write(metadata));
		ctx.status(listed ? 200 : 204);
	}
	
	private record Nuspec(@NonNull String id, @NonNull String version, @NonNull ObjectNode metadata) {}
}
