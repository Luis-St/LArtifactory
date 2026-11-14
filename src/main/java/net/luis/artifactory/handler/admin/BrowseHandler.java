package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.Conditions;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.dto.response.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.repository.RepositoryClass;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.condition.SqlCondition;
import net.luis.utils.io.database.exception.SqlException;
import net.luis.utils.io.database.query.row.SqlRow4;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

import static net.luis.artifactory.database.Tables.*;

/**
 * Read endpoints of the web ui that aggregate on the server, so the ui never has to load every package and file of every repository.<br>
 * All of them only consider the repositories the caller can read and answer anonymous callers for public repositories.<br>
 */
public class BrowseHandler {

	private static final int RECENT_LIMIT = 10;
	private static final Duration ACTIVITY_WINDOW = Duration.ofDays(31);
	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 500;
	private static final int MAX_SEARCH_LIMIT = 50;
	private final Services services;

	public BrowseHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
	}

	private static long queryLong(@NonNull Context ctx, @NonNull String name, long defaultValue, long max) {
		String value = ctx.queryParam(name);
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Math.clamp(Long.parseLong(value.strip()), 0, max);
		} catch (NumberFormatException e) {
			throw HttpError.badRequest("Query parameter " + name + " must be a number");
		}
	}

	private static boolean descending(@NonNull Context ctx, boolean defaultValue) {
		String value = ctx.queryParam("dir");
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		return switch (value.strip().toLowerCase(Locale.ROOT)) {
			case "asc" -> false;
			case "desc" -> true;
			default -> throw HttpError.badRequest("Query parameter dir must be asc or desc");
		};
	}

	@NonNull
	private static String text(@NonNull Context ctx, @NonNull String name) {
		return Objects.requireNonNullElse(ctx.queryParam(name), "").strip();
	}

	private static long number(@Nullable Long value) {
		return value == null ? 0 : value;
	}

	@NonNull
	private static <T> List<T> page(@NonNull List<T> list, long offset, long limit) {
		if (offset >= list.size()) {
			return List.of();
		}
		return list.subList((int) offset, (int) Math.min(list.size(), offset + limit));
	}

	private static PackageResponse.@NonNull VersionResponse version(@NonNull PackageVersionEntity entity) {
		return new PackageResponse.VersionResponse(entity.version(), entity.yanked(), entity.createdAt(), entity.createdBy());
	}

	/**
	 * The readable local and remote repositories, virtual repositories store nothing themselves and are left out of the content queries.<br>
	 */
	@NonNull
	private Map<String, RepositoryEntity> readable(@Nullable Principal principal, @Nullable String type, @Nullable String repository) throws SqlException {
		Map<String, RepositoryEntity> result = new LinkedHashMap<>();
		for (RepositoryEntity entity : this.services.repositories().list()) {
			if ((type == null || type.isEmpty() || entity.type().equals(type)) && (repository == null || repository.isEmpty() || entity.name().equals(repository)) && this.services.auth().canRead(principal, entity)) {
				result.put(entity.name(), entity);
			}
		}
		return result;
	}

	@NonNull
	private Map<String, RepositoryEntity> stored(@NonNull Map<String, RepositoryEntity> repositories) throws SqlException {
		Map<String, RepositoryEntity> result = new LinkedHashMap<>();
		for (RepositoryEntity entity : repositories.values()) {
			if (this.services.repositories().classOf(entity.name()) != RepositoryClass.VIRTUAL) {
				result.put(entity.name(), entity);
			}
		}
		return result;
	}

	@OpenApi(
		summary = "Content counts of the readable repositories, the newest publications and the publications per hour of the last 31 days",
		operationId = "summary",
		path = "/api/summary",
		methods = HttpMethod.GET,
		tags = "Browse",
		queryParams = @OpenApiParam(name = "repository", description = "Only summarize this repository"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SummaryResponse.class))
	)
	public void summary(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().authenticate(ctx);
		Map<String, RepositoryEntity> repositories = this.readable(principal, null, text(ctx, "repository"));
		Map<String, RepositoryEntity> stored = this.stored(repositories);
		String[] names = stored.keySet().toArray(String[]::new);

		Map<String, SqlRow4<String, Long, Long, Instant>> files = new HashMap<>();
		Map<String, SqlRow4<String, Long, Long, Instant>> versions = new HashMap<>();
		List<SummaryResponse.RecentVersion> recent = new ArrayList<>();
		Map<Instant, Map<String, Long>> activity = new TreeMap<>();
		if (names.length > 0) {
			for (var row : this.services.database().from(FILES).select(FILE_REPOSITORY, Sql.count(FILE_PATH, false), Sql.sum(FILE_SIZE), Sql.max(FILE_CREATED_AT))
				.where(Sql.in(FILE_REPOSITORY, names)).groupBy(FILE_REPOSITORY).fetch()) {
				files.put(row.first(), row);
			}
			for (var row : this.services.database().from(PACKAGE_VERSIONS).select(VERSION_REPOSITORY, Sql.count(VERSION_PACKAGE, true), Sql.count(VERSION_VERSION, false), Sql.max(VERSION_CREATED_AT))
				.where(Sql.in(VERSION_REPOSITORY, names)).groupBy(VERSION_REPOSITORY).fetch()) {
				versions.put(row.first(), row);
			}

			List<PackageVersionEntity> newest = this.services.database().from(PACKAGE_VERSIONS).select()
				.where(Sql.in(VERSION_REPOSITORY, names)).orderBy(VERSION_CREATED_AT.descending()).limit(RECENT_LIMIT).fetch();
			for (PackageVersionEntity entity : newest) {
				List<String> tags = this.services.packages().tags(entity.repository(), entity.packageName()).entrySet().stream()
					.filter(tag -> tag.getValue().equals(entity.version())).map(Map.Entry::getKey).toList();
				recent.add(new SummaryResponse.RecentVersion(entity.repository(), stored.get(entity.repository()).type(), entity.packageName(), entity.version(), entity.createdAt(), entity.createdBy(), tags));
			}

			// Package versions are counted for package types and files for generic repositories, which have no packages
			Instant since = Instant.now().minus(ACTIVITY_WINDOW);
			String[] generic = stored.values().stream().filter(entity -> RepositoryType.GENERIC.id().equals(entity.type())).map(RepositoryEntity::name).toArray(String[]::new);
			String[] packaged = stored.values().stream().filter(entity -> !RepositoryType.GENERIC.id().equals(entity.type())).map(RepositoryEntity::name).toArray(String[]::new);
			List<Map.Entry<String, Instant>> events = new ArrayList<>();
			if (packaged.length > 0) {
				this.services.database().from(PACKAGE_VERSIONS).select(VERSION_REPOSITORY, VERSION_CREATED_AT)
					.where(SqlCondition.allOf(Sql.in(VERSION_REPOSITORY, packaged), Sql.before(VERSION_CREATED_AT, since).not())).fetch()
					.forEach(row -> events.add(Map.entry(row.first(), row.second())));
			}
			if (generic.length > 0) {
				this.services.database().from(FILES).select(FILE_REPOSITORY, FILE_CREATED_AT)
					.where(SqlCondition.allOf(Sql.in(FILE_REPOSITORY, generic), Sql.before(FILE_CREATED_AT, since).not())).fetch()
					.forEach(row -> events.add(Map.entry(row.first(), row.second())));
			}
			for (Map.Entry<String, Instant> event : events) {
				activity.computeIfAbsent(event.getValue().truncatedTo(ChronoUnit.HOURS), _ -> new TreeMap<>()).merge(stored.get(event.getKey()).type(), 1L, Long::sum);
			}
		}

		List<SummaryResponse.RepositorySummary> summaries = new ArrayList<>();
		for (RepositoryEntity entity : repositories.values()) {
			var fileRow = files.get(entity.name());
			var versionRow = versions.get(entity.name());
			summaries.add(new SummaryResponse.RepositorySummary(
				entity.name(), entity.type(), this.services.repositories().classOf(entity.name()).name(), entity.description(), entity.publicRead(),
				versionRow == null ? 0 : number(versionRow.second()), versionRow == null ? 0 : number(versionRow.third()),
				fileRow == null ? 0 : number(fileRow.second()), fileRow == null ? 0 : number(fileRow.third()), fileRow == null ? null : fileRow.fourth()
			));
		}
		List<SummaryResponse.ActivityBucket> buckets = activity.entrySet().stream().map(entry -> new SummaryResponse.ActivityBucket(entry.getKey(), entry.getValue())).toList();
		ctx.json(new SummaryResponse(summaries, recent, buckets));
	}

	@OpenApi(
		summary = "List the packages of all readable repositories, sorted and paged on the server",
		operationId = "listAllPackages",
		path = "/api/packages",
		methods = HttpMethod.GET,
		tags = "Browse",
		queryParams = {
			@OpenApiParam(name = "type", description = "Only packages of repositories of this type"),
			@OpenApiParam(name = "repository", description = "Only packages of this repository"),
			@OpenApiParam(name = "q", description = "Filter packages by name"),
			@OpenApiParam(name = "sort", description = "name, repository, versions, size or updated (default)"),
			@OpenApiParam(name = "dir", description = "asc or desc, default desc for updated, versions and size"),
			@OpenApiParam(name = "offset", type = Integer.class),
			@OpenApiParam(name = "limit", type = Integer.class, description = "Default " + DEFAULT_LIMIT + ", at most " + MAX_LIMIT)
		},
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = PackageSummaryResponse[].class),
			headers = @OpenApiParam(name = RepositoryAdminHandler.TOTAL_COUNT_HEADER, type = Long.class)
		)
	)
	public void packages(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().authenticate(ctx);
		Map<String, RepositoryEntity> stored = this.stored(this.readable(principal, text(ctx, "type"), text(ctx, "repository")));
		String query = text(ctx, "q");
		String sort = text(ctx, "sort").isEmpty() ? "updated" : text(ctx, "sort");
		boolean descending = descending(ctx, Set.of("updated", "versions", "size").contains(sort));
		long offset = queryLong(ctx, "offset", 0, Long.MAX_VALUE);
		long limit = queryLong(ctx, "limit", DEFAULT_LIMIT, MAX_LIMIT);
		if (stored.isEmpty()) {
			ctx.header(RepositoryAdminHandler.TOTAL_COUNT_HEADER, "0");
			ctx.json(List.of());
			return;
		}
		String[] names = stored.keySet().toArray(String[]::new);

		// One row per package with its version count and last publication, and the size of its files, the versions are only loaded for the page
		SqlCondition condition = Sql.in(VERSION_REPOSITORY, names);
		if (!query.isEmpty()) {
			condition = SqlCondition.allOf(condition, Conditions.containsIgnoreCase(VERSION_PACKAGE, query));
		}
		var groups = this.services.database().from(PACKAGE_VERSIONS).select(VERSION_REPOSITORY, VERSION_PACKAGE, Sql.count(VERSION_VERSION, false), Sql.max(VERSION_CREATED_AT))
			.where(condition).groupBy(VERSION_REPOSITORY, VERSION_PACKAGE).fetch();
		Map<String, long[]> sizes = new HashMap<>();
		for (var row : this.services.database().from(FILES).select(FILE_REPOSITORY, FILE_PACKAGE, Sql.count(FILE_PATH, false), Sql.sum(FILE_SIZE))
			.where(SqlCondition.allOf(Sql.in(FILE_REPOSITORY, names), Sql.isNull(FILE_PACKAGE).not())).groupBy(FILE_REPOSITORY, FILE_PACKAGE).fetch()) {
			sizes.put(row.first() + "\n" + row.second(), new long[] { number(row.third()), number(row.fourth()) });
		}
		Function<SqlRow4<String, String, Long, Instant>, long[]> sizeOf = row -> sizes.getOrDefault(row.first() + "\n" + row.second(), new long[2]);
		Comparator<SqlRow4<String, String, Long, Instant>> comparator = switch (sort) {
			case "name" -> Comparator.comparing(row -> row.second().toLowerCase(Locale.ROOT));
			case "repository" -> Comparator.comparing(SqlRow4::first);
			case "versions" -> Comparator.comparingLong(row -> number(row.third()));
			case "size" -> Comparator.comparingLong(row -> sizeOf.apply(row)[1]);
			case "updated" -> Comparator.comparing(SqlRow4::fourth, Comparator.nullsFirst(Comparator.naturalOrder()));
			default -> throw HttpError.badRequest("Query parameter sort must be name, repository, versions, size or updated");
		};
		comparator = comparator.thenComparing(SqlRow4::second).thenComparing(SqlRow4::first);
		List<SqlRow4<String, String, Long, Instant>> sorted = new ArrayList<>(groups);
		sorted.sort(descending ? comparator.reversed() : comparator);
		List<SqlRow4<String, String, Long, Instant>> page = page(sorted, offset, limit);

		Map<String, List<String>> byRepository = new LinkedHashMap<>();
		page.forEach(row -> byRepository.computeIfAbsent(row.first(), _ -> new ArrayList<>()).add(row.second()));
		Map<String, Map<String, List<PackageVersionEntity>>> versions = new HashMap<>();
		Map<String, Map<String, Map<String, String>>> tags = new HashMap<>();
		for (Map.Entry<String, List<String>> entry : byRepository.entrySet()) {
			versions.put(entry.getKey(), this.services.packages().versions(entry.getKey(), entry.getValue()));
			tags.put(entry.getKey(), this.services.packages().tags(entry.getKey(), entry.getValue()));
		}
		List<PackageSummaryResponse> result = new ArrayList<>();
		for (var row : page) {
			long[] size = sizeOf.apply(row);
			result.add(new PackageSummaryResponse(row.first(), stored.get(row.first()).type(), row.second(),
				versions.get(row.first()).getOrDefault(row.second(), List.of()).stream().map(BrowseHandler::version).toList(),
				tags.get(row.first()).getOrDefault(row.second(), Map.of()), size[0], size[1], row.fourth()));
		}
		ctx.header(RepositoryAdminHandler.TOTAL_COUNT_HEADER, String.valueOf(sorted.size()));
		ctx.json(result);
	}

	@OpenApi(
		summary = "List the files of all readable repositories, newest first by default",
		operationId = "listAllFiles",
		path = "/api/files",
		methods = HttpMethod.GET,
		tags = "Browse",
		queryParams = {
			@OpenApiParam(name = "type", description = "Only files of repositories of this type"),
			@OpenApiParam(name = "repository", description = "Only files of this repository"),
			@OpenApiParam(name = "q", description = "Filter files by path"),
			@OpenApiParam(name = "sort", description = "path, repository, size or uploaded (default)"),
			@OpenApiParam(name = "dir", description = "asc or desc, default desc for uploaded and size"),
			@OpenApiParam(name = "offset", type = Integer.class),
			@OpenApiParam(name = "limit", type = Integer.class, description = "Default " + DEFAULT_LIMIT + ", at most " + MAX_LIMIT)
		},
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = FileResponse[].class),
			headers = @OpenApiParam(name = RepositoryAdminHandler.TOTAL_COUNT_HEADER, type = Long.class)
		)
	)
	public void files(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().authenticate(ctx);
		Map<String, RepositoryEntity> stored = this.stored(this.readable(principal, text(ctx, "type"), text(ctx, "repository")));
		String query = text(ctx, "q");
		String sort = text(ctx, "sort").isEmpty() ? "uploaded" : text(ctx, "sort");
		boolean descending = descending(ctx, Set.of("uploaded", "size").contains(sort));
		long offset = queryLong(ctx, "offset", 0, Long.MAX_VALUE);
		long limit = queryLong(ctx, "limit", DEFAULT_LIMIT, MAX_LIMIT);
		if (stored.isEmpty()) {
			ctx.header(RepositoryAdminHandler.TOTAL_COUNT_HEADER, "0");
			ctx.json(List.of());
			return;
		}
		SqlCondition condition = Sql.in(FILE_REPOSITORY, stored.keySet().toArray(String[]::new));
		if (!query.isEmpty()) {
			condition = SqlCondition.allOf(condition, Conditions.containsIgnoreCase(FILE_PATH, query));
		}
		var column = switch (sort) {
			case "path" -> FILE_PATH;
			case "repository" -> FILE_REPOSITORY;
			case "size" -> FILE_SIZE;
			case "uploaded" -> FILE_CREATED_AT;
			default -> throw HttpError.badRequest("Query parameter sort must be path, repository, size or uploaded");
		};
		var select = this.services.database().from(FILES).select().where(condition)
			.orderBy(descending ? column.descending() : column.ascending(), FILE_REPOSITORY.ascending(), FILE_PATH.ascending())
			.limit(limit);
		if (offset > 0) {
			select = select.offset(offset);
		}
		ctx.header(RepositoryAdminHandler.TOTAL_COUNT_HEADER, String.valueOf(this.services.database().from(FILES).select().where(condition).count()));
		ctx.json(select.fetch().stream().map(FileResponse::of).toList());
	}

	@OpenApi(
		summary = "Search the packages of all readable repositories by name",
		operationId = "search",
		path = "/api/search",
		methods = HttpMethod.GET,
		tags = "Browse",
		queryParams = {
			@OpenApiParam(name = "q", required = true),
			@OpenApiParam(name = "limit", type = Integer.class, description = "At most " + MAX_SEARCH_LIMIT)
		},
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = SearchResultResponse[].class))
	)
	public void search(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().authenticate(ctx);
		String query = text(ctx, "q");
		long limit = queryLong(ctx, "limit", 10, MAX_SEARCH_LIMIT);
		Map<String, RepositoryEntity> stored = this.stored(this.readable(principal, null, null));
		if (query.isEmpty() || stored.isEmpty()) {
			ctx.json(List.of());
			return;
		}
		var rows = this.services.database().from(PACKAGE_VERSIONS).select(VERSION_REPOSITORY, VERSION_PACKAGE)
			.where(SqlCondition.allOf(Sql.in(VERSION_REPOSITORY, stored.keySet().toArray(String[]::new)), Conditions.containsIgnoreCase(VERSION_PACKAGE, query)))
			.distinct().orderBy(VERSION_PACKAGE.ascending(), VERSION_REPOSITORY.ascending()).limit(limit).fetch();
		ctx.json(rows.stream().map(row -> new SearchResultResponse(row.first(), stored.get(row.first()).type(), row.second())).toList());
	}

	@OpenApi(
		summary = "Get one package of a repository with its versions, tags and files",
		operationId = "getPackage",
		path = "/api/repositories/{name}/packages/{package}",
		methods = HttpMethod.GET,
		tags = "Repositories",
		pathParams = {
			@OpenApiParam(name = "name", required = true),
			@OpenApiParam(name = "package", required = true, description = "The package name, a slash (npm scopes) is sent encoded")
		},
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = PackageDetailResponse.class)),
			@OpenApiResponse(status = "404", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void pkg(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.readableRepository(ctx);
		String name = ctx.pathParam("package");
		List<PackageVersionEntity> versions = this.services.packages().versions(repository.name(), name);
		if (versions.isEmpty()) {
			throw HttpError.notFound("No such package: " + name);
		}
		List<FileResponse> files = this.services.artifacts().listByPackage(repository.name(), name).stream().map(FileResponse::of).toList();
		ctx.json(new PackageDetailResponse(name, versions.stream().map(BrowseHandler::version).toList(), this.services.packages().tags(repository.name(), name), files));
	}

	@OpenApi(
		summary = "List the direct content of a folder of a repository",
		operationId = "listTree",
		path = "/api/repositories/{name}/tree",
		methods = HttpMethod.GET,
		tags = "Repositories",
		pathParams = @OpenApiParam(name = "name", required = true),
		queryParams = @OpenApiParam(name = "prefix", description = "The folder with a trailing slash, empty for the root"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = TreeResponse.class))
	)
	public void tree(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.readableRepository(ctx);
		String prefix = text(ctx, "prefix");
		if (!prefix.isEmpty() && !prefix.endsWith("/")) {
			prefix += "/";
		}
		var listing = this.services.artifacts().listDirectory(repository.name(), prefix);
		List<String> folders = listing.directories().stream().map(folder -> folder.endsWith("/") ? folder.substring(0, folder.length() - 1) : folder).toList();
		ctx.json(new TreeResponse(prefix, folders, listing.files().stream().map(FileResponse::of).toList()));
	}

	@OpenApi(
		summary = "Create a single use download ticket for one path, the path can then be fetched once within a minute with ?ticket=",
		description = "Lets a browser download with a plain link that streams to disk, the ticket grants at most READ access to exactly that path",
		operationId = "createDownloadTicket",
		path = "/api/downloads",
		methods = HttpMethod.POST,
		tags = "Browse",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = DownloadRequest.class)),
		responses = @OpenApiResponse(status = "201", content = @OpenApiContent(from = DownloadResponse.class))
	)
	public void download(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAuthenticated(ctx, "Basic realm=\"artifactory\"");
		DownloadRequest request = ctx.bodyAsClass(DownloadRequest.class);
		String path = request.path();
		if (path == null || !path.startsWith("/") || path.startsWith("/api/") || path.contains("?") || path.length() > 4096) {
			throw HttpError.badRequest("path must be the absolute path of a download");
		}
		String ticket = this.services.auth().createTicket(principal, path);
		ctx.status(201).json(new DownloadResponse(path + "?" + net.luis.artifactory.auth.AuthService.TICKET_PARAMETER + "=" + ticket));
	}
	
	/**
	 * @param path The raw (percent-encoded) path of the file, e.g. {@code /maven/releases/net/example/lib/1.0/lib-1.0.jar}
	 */
	public record DownloadRequest(@Nullable String path) {}
	
	/**
	 * @param url The path with the ticket, valid for one request within a minute
	 */
	public record DownloadResponse(@NonNull String url) {}
	
	@NonNull
	private RepositoryEntity readableRepository(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.services.repositories().get(ctx.pathParam("name"));
		if (repository == null) {
			throw HttpError.notFound("No such repository: " + ctx.pathParam("name"));
		}
		this.services.auth().authorize(ctx, repository, AccessLevel.READ, "Basic realm=\"" + repository.name() + "\"");
		return repository;
	}
}
