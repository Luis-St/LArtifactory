package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.entity.*;
import net.luis.artifactory.dto.request.CreateRepositoryRequest;
import net.luis.artifactory.dto.request.UpdateRepositoryRequest;
import net.luis.artifactory.dto.response.*;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

public class RepositoryAdminHandler {
	
	private static final int DEFAULT_PACKAGE_LIMIT = 100;
	private static final int MAX_PACKAGE_LIMIT = 500;
	private static final int DEFAULT_FILE_LIMIT = 1000;
	private static final int MAX_FILE_LIMIT = 5000;
	public static final String TOTAL_COUNT_HEADER = "X-Total-Count";
	private final Services services;
	
	public RepositoryAdminHandler(@NonNull Services services) {
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
	
	@NonNull
	private static String validateRemoteUrl(CreateRepositoryRequest.@NonNull Remote remote) {
		String url = remote.url();
		if (url == null || url.isBlank()) {
			throw HttpError.badRequest("remote.url is required for a remote repository");
		}
		String trimmed = url.strip();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
			throw HttpError.badRequest("remote.url must be an http or https url");
		}
		if (trimmed.length() > 1024) {
			throw HttpError.badRequest("remote.url must have at most 1024 characters");
		}
		return trimmed;
	}
	
	@NonNull
	private static List<String> normalizeMembers(@NonNull List<String> members) {
		List<String> result = new ArrayList<>();
		for (String member : members) {
			if (member == null || member.isBlank()) {
				continue;
			}
			String trimmed = member.strip();
			if (!result.contains(trimmed)) {
				result.add(trimmed);
			}
		}
		return result;
	}
	
	@org.jspecify.annotations.Nullable
	private static String blankToNull(@org.jspecify.annotations.Nullable String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
	
	/**
	 * npm and Cargo forbid overwriting a published version, their clients and caches assume a version never changes,
	 * so the handlers of these types never redeploy and the flag is rejected instead of silently ignored.<br>
	 */
	private static void requireRedeploySupported(@NonNull RepositoryType type, @Nullable Boolean allowRedeploy) {
		if (Boolean.TRUE.equals(allowRedeploy) && (type == RepositoryType.NPM || type == RepositoryType.CARGO)) {
			throw HttpError.badRequest(type.id() + " repositories do not support redeploys, published versions are immutable");
		}
	}
	
	@OpenApi(
		summary = "List repositories readable by the caller",
		operationId = "listRepositories",
		path = "/api/repositories",
		methods = HttpMethod.GET,
		tags = "Repositories",
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = RepositoryResponse[].class))
	)
	public void list(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().authenticate(ctx);
		List<RepositoryResponse> result = new ArrayList<>();
		for (RepositoryEntity repository : this.services.repositories().list()) {
			if (this.services.auth().canRead(principal, repository)) {
				result.add(this.response(ctx, principal, repository));
			}
		}
		ctx.json(result);
	}
	
	/**
	 * Builds a repository response enriched with its class (local/remote/virtual), the remote/virtual settings and the access level of the caller.<br>
	 */
	@NonNull
	RepositoryResponse response(@NonNull Context ctx, @Nullable Principal principal, @NonNull RepositoryEntity repository) throws Exception {
		RepositoryClass kind = this.services.repositories().classOf(repository.name());
		RepositoryResponse.RemoteInfo remote = null;
		List<String> members = List.of();
		if (kind == RepositoryClass.REMOTE) {
			var config = this.services.repositories().remoteConfig(repository.name());
			remote = config == null ? null : RepositoryResponse.RemoteInfo.of(config);
		} else if (kind == RepositoryClass.VIRTUAL) {
			members = this.services.repositories().virtualMembers(repository.name()).stream().map(net.luis.artifactory.database.entity.VirtualMemberEntity::member).toList();
		}
		AccessLevel level = principal == null ? null : this.services.auth().effectiveLevel(principal, repository.name());
		return RepositoryResponse.of(repository, this.repositoryUrl(ctx, repository), kind.name(), remote, members, level == null ? null : level.name());
	}
	
	/**
	 * The url clients use for the repository, the root of a host mapped to it with {@code ARTIFACTORY_HOST_REPOSITORIES} if there is one.<br>
	 */
	@NonNull
	private String repositoryUrl(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		String baseUrl = Requests.baseUrl(ctx, this.services.config());
		for (Map.Entry<String, String> mapping : this.services.config().hostRepositories().entrySet()) {
			if (mapping.getValue().equals(repository.name())) {
				return baseUrl.substring(0, baseUrl.indexOf("://") + 3) + mapping.getKey();
			}
		}
		return baseUrl + "/" + repository.type() + "/" + repository.name();
	}
	
	@OpenApi(
		summary = "Get a repository",
		operationId = "getRepository",
		path = "/api/repositories/{name}",
		methods = HttpMethod.GET,
		tags = "Repositories",
		pathParams = @OpenApiParam(name = "name", required = true),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = RepositoryResponse.class)),
			@OpenApiResponse(status = "404", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void get(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.readable(ctx);
		ctx.json(this.response(ctx, this.services.auth().authenticate(ctx), repository));
	}
	
	@OpenApi(
		summary = "Create a repository",
		description = "Types: maven, generic, pypi, npm, nuget, cargo, oci",
		operationId = "createRepository",
		path = "/api/repositories",
		methods = HttpMethod.POST,
		tags = "Repositories",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreateRepositoryRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = RepositoryResponse.class)),
			@OpenApiResponse(status = "400", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "409", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void create(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAdmin(ctx);
		CreateRepositoryRequest request = ctx.bodyAsClass(CreateRepositoryRequest.class);
		if (request.name() == null || !RepositoryService.NAME_PATTERN.matcher(request.name()).matches()) {
			throw HttpError.badRequest("Invalid repository name, allowed: " + RepositoryService.NAME_PATTERN.pattern());
		}
		RepositoryType type = RepositoryType.byId(request.type());
		if (type == null) {
			throw HttpError.badRequest("Invalid repository type, allowed: " + Arrays.stream(RepositoryType.values()).map(RepositoryType::id).toList());
		}
		if (this.services.repositories().get(request.name()) != null) {
			throw HttpError.conflict("Repository " + request.name() + " already exists");
		}
		
		requireRedeploySupported(type, request.allowRedeploy());
		boolean remote = request.remote() != null;
		boolean virtual = request.members() != null;
		if (remote && virtual) {
			throw HttpError.badRequest("A repository can not be both remote and virtual");
		}
		if ((remote || virtual) && type != RepositoryType.MAVEN && type != RepositoryType.GENERIC) {
			throw HttpError.badRequest("Remote and virtual repositories currently support only the maven and generic types");
		}
		String remoteUrl = null;
		if (remote) {
			remoteUrl = validateRemoteUrl(request.remote());
		}
		if (virtual) {
			this.validateMembers(request.members(), type);
		}
		
		RepositoryClass kind = remote ? RepositoryClass.REMOTE : virtual ? RepositoryClass.VIRTUAL : RepositoryClass.LOCAL;
		RemoteConfigEntity remoteConfig = null;
		if (remote) {
			CreateRepositoryRequest.Remote config = Objects.requireNonNull(request.remote());
			remoteConfig = new RemoteConfigEntity(request.name(), Objects.requireNonNull(remoteUrl), Boolean.TRUE.equals(config.cacheEnabled()), blankToNull(config.username()), blankToNull(config.password()));
		}
		List<String> members = virtual ? normalizeMembers(Objects.requireNonNull(request.members())) : List.of();
		RepositoryEntity repository = this.services.repositories().create(request.name(), type, kind, request.description(), Boolean.TRUE.equals(request.publicRead()), Boolean.TRUE.equals(request.allowRedeploy()), remoteConfig, members);
		String kindName = kind.name().toLowerCase(Locale.ROOT);
		this.services.audit().record(principal.username(), AuditService.REPOSITORY_CREATE, repository.name(), type.id(), Requests.clientIp(ctx, this.services.config()), kindName + (repository.publicRead() ? ", publicRead" : ""));
		this.services.webhooks().dispatch(WebhookService.REPOSITORY_CREATED, repository.name(), type.id());
		ctx.status(201).json(this.response(ctx, principal, repository));
	}
	
	@OpenApi(
		summary = "Update a repository",
		operationId = "updateRepository",
		path = "/api/repositories/{name}",
		methods = HttpMethod.PATCH,
		tags = "Repositories",
		pathParams = @OpenApiParam(name = "name", required = true),
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = UpdateRepositoryRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = RepositoryResponse.class))
	)
	public void update(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAdmin(ctx);
		RepositoryEntity repository = this.require(ctx);
		UpdateRepositoryRequest request = ctx.bodyAsClass(UpdateRepositoryRequest.class);
		RepositoryType type = Objects.requireNonNull(RepositoryType.byId(repository.type()));
		requireRedeploySupported(type, request.allowRedeploy());
		
		// Everything is validated before anything is written, a rejected request leaves the repository unchanged
		RepositoryClass kind = this.services.repositories().classOf(repository.name());
		RemoteConfigEntity remote = null;
		if (request.remote() != null) {
			if (kind != RepositoryClass.REMOTE) {
				throw HttpError.badRequest("Repository " + repository.name() + " is not a remote repository");
			}
			String url = validateRemoteUrl(request.remote());
			// Omitted fields keep their stored value, an empty username removes the upstream credentials
			RemoteConfigEntity stored = this.services.repositories().remoteConfig(repository.name());
			CreateRepositoryRequest.Remote changes = request.remote();
			boolean cacheEnabled = changes.cacheEnabled() != null ? changes.cacheEnabled() : stored != null && stored.cacheEnabled();
			String username = changes.username() != null ? blankToNull(changes.username()) : stored == null ? null : stored.username();
			String password = username == null ? null : changes.password() != null ? blankToNull(changes.password()) : stored == null ? null : stored.password();
			remote = new RemoteConfigEntity(repository.name(), url, cacheEnabled, username, password);
		}
		List<String> members = null;
		if (request.members() != null) {
			if (kind != RepositoryClass.VIRTUAL) {
				throw HttpError.badRequest("Repository " + repository.name() + " is not a virtual repository");
			}
			this.validateMembers(request.members(), type);
			members = normalizeMembers(request.members());
			if (members.contains(repository.name())) {
				throw HttpError.badRequest("A virtual repository can not be its own member");
			}
		}
		this.services.repositories().update(repository.name(), request.description(), request.publicRead(), request.allowRedeploy(), remote, members);
		this.services.audit().record(principal.username(), AuditService.REPOSITORY_UPDATE, repository.name(), null, Requests.clientIp(ctx, this.services.config()), null);
		ctx.json(this.response(ctx, principal, Objects.requireNonNull(this.services.repositories().get(repository.name()))));
	}
	
	@OpenApi(
		summary = "Delete a repository with all its content",
		operationId = "deleteRepository",
		path = "/api/repositories/{name}",
		methods = HttpMethod.DELETE,
		tags = "Repositories",
		pathParams = @OpenApiParam(name = "name", required = true),
		responses = @OpenApiResponse(status = "204")
	)
	public void delete(@NonNull Context ctx) throws Exception {
		Principal principal = this.services.auth().requireAdmin(ctx);
		RepositoryEntity repository = this.require(ctx);
		this.services.repositories().delete(repository.name());
		this.services.audit().record(principal.username(), AuditService.REPOSITORY_DELETE, repository.name(), repository.type(), Requests.clientIp(ctx, this.services.config()), null);
		this.services.webhooks().dispatch(WebhookService.REPOSITORY_DELETED, repository.name(), repository.type());
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "List the packages and versions of a repository",
		operationId = "listPackages",
		path = "/api/repositories/{name}/packages",
		methods = HttpMethod.GET,
		tags = "Repositories",
		pathParams = @OpenApiParam(name = "name", required = true),
		queryParams = {
			@OpenApiParam(name = "q", description = "Filter packages by name"),
			@OpenApiParam(name = "offset", type = Integer.class, description = "Number of packages to skip"),
			@OpenApiParam(name = "limit", type = Integer.class, description = "Maximum number of packages (default " + DEFAULT_PACKAGE_LIMIT + ", at most " + MAX_PACKAGE_LIMIT + ")")
		},
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = PackageResponse[].class),
			headers = @OpenApiParam(name = TOTAL_COUNT_HEADER, type = Long.class, description = "Total number of matching packages")
		)
	)
	public void packages(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.readable(ctx);
		String query = Objects.requireNonNullElse(ctx.queryParam("q"), "").strip();
		long offset = queryLong(ctx, "offset", 0, Long.MAX_VALUE);
		long limit = queryLong(ctx, "limit", DEFAULT_PACKAGE_LIMIT, MAX_PACKAGE_LIMIT);
		// Versions and tags of the whole page are loaded with one query each
		List<String> names = this.services.packages().packageNames(repository.name(), query, offset, limit);
		Map<String, List<PackageVersionEntity>> versionsByName = this.services.packages().versions(repository.name(), names);
		Map<String, Map<String, String>> tagsByName = this.services.packages().tags(repository.name(), names);
		List<PackageResponse> result = new ArrayList<>();
		for (String packageName : names) {
			List<PackageResponse.VersionResponse> versions = versionsByName.getOrDefault(packageName, List.of()).stream()
				.map(version -> new PackageResponse.VersionResponse(version.version(), version.yanked(), version.createdAt(), version.createdBy()))
				.toList();
			result.add(new PackageResponse(packageName, versions, tagsByName.getOrDefault(packageName, Map.of())));
		}
		ctx.header(TOTAL_COUNT_HEADER, String.valueOf(this.services.packages().countPackages(repository.name(), query)));
		ctx.json(result);
	}
	
	@OpenApi(
		summary = "Delete a package version (or the whole package if no version is given) including its files",
		operationId = "deletePackage",
		path = "/api/repositories/{name}/packages",
		methods = HttpMethod.DELETE,
		tags = "Repositories",
		pathParams = @OpenApiParam(name = "name", required = true),
		queryParams = {
			@OpenApiParam(name = "package", required = true),
			@OpenApiParam(name = "version")
		},
		responses = @OpenApiResponse(status = "204")
	)
	public void deletePackage(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.require(ctx);
		Principal principal = this.services.auth().authorize(ctx, repository, AccessLevel.DELETE, "Basic realm=\"" + repository.name() + "\"");
		String packageName = ctx.queryParam("package");
		String version = ctx.queryParam("version");
		if (packageName == null || packageName.isBlank()) {
			throw HttpError.badRequest("Missing query parameter: package");
		}
		
		this.services.artifacts().transaction(work -> {
			List<String> versions = version != null ? List.of(version) : this.services.packages().versions(work, repository.name(), packageName).stream().map(PackageVersionEntity::version).toList();
			boolean deleted = false;
			for (String current : versions) {
				deleted |= this.services.packages().delete(work, repository.name(), packageName, current);
				deleted |= this.services.artifacts().deleteVersion(work, repository.name(), packageName, current) > 0;
			}
			if (!deleted) {
				throw HttpError.notFound();
			}
			if (version == null) {
				this.services.packages().deleteTags(work, repository.name(), packageName);
			} else {
				for (Map.Entry<String, String> tag : this.services.packages().tags(work, repository.name(), packageName).entrySet()) {
					if (tag.getValue().equals(version)) {
						this.services.packages().deleteTag(work, repository.name(), packageName, tag.getKey());
					}
				}
			}
			return null;
		});
		String target = version == null ? packageName : packageName + "@" + version;
		this.services.audit().record(principal == null ? null : principal.username(), AuditService.PACKAGE_DELETE, repository.name(), target, Requests.clientIp(ctx, this.services.config()), null);
		this.services.webhooks().dispatch(WebhookService.PACKAGE_DELETED, repository.name(), target);
		ctx.status(204);
	}
	
	@OpenApi(
		summary = "List the files of a repository",
		operationId = "listFiles",
		path = "/api/repositories/{name}/files",
		methods = HttpMethod.GET,
		tags = "Repositories",
		pathParams = @OpenApiParam(name = "name", required = true),
		queryParams = {
			@OpenApiParam(name = "prefix", description = "Only list files below this path"),
			@OpenApiParam(name = "offset", type = Integer.class, description = "Number of files to skip"),
			@OpenApiParam(name = "limit", type = Integer.class, description = "Maximum number of files (default " + DEFAULT_FILE_LIMIT + ", at most " + MAX_FILE_LIMIT + ")")
		},
		responses = @OpenApiResponse(
			status = "200",
			content = @OpenApiContent(from = FileResponse[].class),
			headers = @OpenApiParam(name = TOTAL_COUNT_HEADER, type = Long.class, description = "Total number of matching files")
		)
	)
	public void files(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.readable(ctx);
		String prefix = Objects.requireNonNullElse(ctx.queryParam("prefix"), "");
		long offset = queryLong(ctx, "offset", 0, Long.MAX_VALUE);
		long limit = queryLong(ctx, "limit", DEFAULT_FILE_LIMIT, MAX_FILE_LIMIT);
		ctx.header(TOTAL_COUNT_HEADER, String.valueOf(this.services.artifacts().count(repository.name(), prefix)));
		ctx.json(this.services.artifacts().list(repository.name(), prefix, offset, limit).stream().map(FileResponse::of).toList());
	}
	
	private void validateMembers(@NonNull List<String> members, @NonNull RepositoryType type) throws Exception {
		List<String> normalized = normalizeMembers(members);
		if (normalized.isEmpty()) {
			throw HttpError.badRequest("A virtual repository needs at least one member");
		}
		for (String member : normalized) {
			RepositoryEntity memberRepository = this.services.repositories().get(member);
			if (memberRepository == null) {
				throw HttpError.notFound("No such member repository: " + member);
			}
			if (!memberRepository.type().equals(type.id())) {
				throw HttpError.badRequest("Member " + member + " must have the same type (" + type.id() + ")");
			}
			if (this.services.repositories().classOf(member) == RepositoryClass.VIRTUAL) {
				throw HttpError.badRequest("Member " + member + " is itself a virtual repository, which is not allowed");
			}
		}
	}
	
	@NonNull
	private RepositoryEntity require(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.services.repositories().get(ctx.pathParam("name"));
		if (repository == null) {
			throw HttpError.notFound("No such repository: " + ctx.pathParam("name"));
		}
		return repository;
	}
	
	@NonNull
	private RepositoryEntity readable(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.require(ctx);
		this.services.auth().authorize(ctx, repository, AccessLevel.READ, "Basic realm=\"" + repository.name() + "\"");
		return repository;
	}
}
