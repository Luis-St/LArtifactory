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
import net.luis.artifactory.repository.AuditService;
import net.luis.artifactory.repository.RepositoryClass;
import net.luis.artifactory.repository.RepositoryService;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.artifactory.repository.WebhookService;
import org.jspecify.annotations.NonNull;

import java.util.*;

public class RepositoryAdminHandler {
	
	private final Services services;
	
	public RepositoryAdminHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
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
		String baseUrl = Requests.baseUrl(ctx, this.services.config());
		List<RepositoryResponse> result = new ArrayList<>();
		for (RepositoryEntity repository : this.services.repositories().list()) {
			if (this.services.auth().canRead(principal, repository)) {
				result.add(this.response(repository, baseUrl));
			}
		}
		ctx.json(result);
	}

	/**
	 * Builds a repository response enriched with its class (local/remote/virtual) and the remote/virtual settings.<br>
	 */
	private @NonNull RepositoryResponse response(@NonNull RepositoryEntity repository, @NonNull String baseUrl) throws Exception {
		RepositoryClass kind = this.services.repositories().classOf(repository.name());
		RepositoryResponse.RemoteInfo remote = null;
		List<String> members = List.of();
		if (kind == RepositoryClass.REMOTE) {
			var config = this.services.repositories().remoteConfig(repository.name());
			remote = config == null ? null : RepositoryResponse.RemoteInfo.of(config);
		} else if (kind == RepositoryClass.VIRTUAL) {
			members = this.services.repositories().virtualMembers(repository.name()).stream().map(net.luis.artifactory.database.entity.VirtualMemberEntity::member).toList();
		}
		return RepositoryResponse.of(repository, baseUrl, kind.name(), remote, members);
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
		ctx.json(this.response(repository, Requests.baseUrl(ctx, this.services.config())));
	}
	
	@OpenApi(
		summary = "Create a repository",
		description = "Types: maven, generic, pypi, npm, nuget, cargo",
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

		RepositoryEntity repository = this.services.repositories().create(request.name(), type, request.description(), Boolean.TRUE.equals(request.publicRead()), Boolean.TRUE.equals(request.allowRedeploy()));
		String kind = "local";
		if (remote) {
			CreateRepositoryRequest.Remote config = Objects.requireNonNull(request.remote());
			this.services.repositories().setRemoteConfig(repository.name(), remoteUrl, Boolean.TRUE.equals(config.cacheEnabled()), blankToNull(config.username()), blankToNull(config.password()));
			kind = "remote";
		} else if (virtual) {
			this.services.repositories().setVirtualMembers(repository.name(), normalizeMembers(request.members()));
			kind = "virtual";
		}
		this.services.audit().record(principal.username(), AuditService.REPOSITORY_CREATE, repository.name(), type.id(), Requests.clientIp(ctx, this.services.config()), kind + (repository.publicRead() ? ", publicRead" : ""));
		this.services.webhooks().dispatch(WebhookService.REPOSITORY_CREATED, repository.name(), type.id());
		ctx.status(201).json(this.response(repository, Requests.baseUrl(ctx, this.services.config())));
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
		this.services.repositories().update(repository.name(), request.description(), request.publicRead(), request.allowRedeploy());

		RepositoryClass kind = this.services.repositories().classOf(repository.name());
		if (request.remote() != null) {
			if (kind != RepositoryClass.REMOTE) {
				throw HttpError.badRequest("Repository " + repository.name() + " is not a remote repository");
			}
			String url = validateRemoteUrl(request.remote());
			this.services.repositories().setRemoteConfig(repository.name(), url, Boolean.TRUE.equals(request.remote().cacheEnabled()), blankToNull(request.remote().username()), blankToNull(request.remote().password()));
		}
		if (request.members() != null) {
			if (kind != RepositoryClass.VIRTUAL) {
				throw HttpError.badRequest("Repository " + repository.name() + " is not a virtual repository");
			}
			this.validateMembers(request.members(), Objects.requireNonNull(RepositoryType.byId(repository.type())));
			this.services.repositories().setVirtualMembers(repository.name(), normalizeMembers(request.members()));
		}
		this.services.audit().record(principal.username(), AuditService.REPOSITORY_UPDATE, repository.name(), null, Requests.clientIp(ctx, this.services.config()), null);
		ctx.json(this.response(Objects.requireNonNull(this.services.repositories().get(repository.name())), Requests.baseUrl(ctx, this.services.config())));
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
		queryParams = @OpenApiParam(name = "q", description = "Filter packages by name"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = PackageResponse[].class))
	)
	public void packages(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.readable(ctx);
		String query = Objects.requireNonNullElse(ctx.queryParam("q"), "").toLowerCase(Locale.ROOT);
		List<PackageResponse> result = new ArrayList<>();
		for (Map.Entry<String, List<PackageVersionEntity>> entry : this.services.packages().grouped(repository.name()).entrySet()) {
			if (!entry.getKey().toLowerCase(Locale.ROOT).contains(query)) {
				continue;
			}
			List<PackageResponse.VersionResponse> versions = entry.getValue().stream()
				.map(version -> new PackageResponse.VersionResponse(version.version(), version.yanked(), version.createdAt(), version.createdBy()))
				.toList();
			result.add(new PackageResponse(entry.getKey(), versions, this.services.packages().tags(repository.name(), entry.getKey())));
		}
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
		
		List<String> versions = version != null ? List.of(version) : this.services.packages().versions(repository.name(), packageName).stream().map(PackageVersionEntity::version).toList();
		boolean deleted = false;
		for (String current : versions) {
			deleted |= this.services.packages().delete(repository.name(), packageName, current);
			deleted |= this.services.artifacts().deleteVersion(repository.name(), packageName, current) > 0;
		}
		if (version == null) {
			this.services.packages().deleteTags(repository.name(), packageName);
		} else {
			for (Map.Entry<String, String> tag : this.services.packages().tags(repository.name(), packageName).entrySet()) {
				if (tag.getValue().equals(version)) {
					this.services.packages().deleteTag(repository.name(), packageName, tag.getKey());
				}
			}
		}
		if (!deleted) {
			throw HttpError.notFound();
		}
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
		queryParams = @OpenApiParam(name = "prefix", description = "Only list files below this path"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = FileResponse[].class))
	)
	public void files(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.readable(ctx);
		String prefix = Objects.requireNonNullElse(ctx.queryParam("prefix"), "");
		ctx.json(this.services.artifacts().list(repository.name(), prefix).stream().map(FileResponse::of).toList());
	}
	
	private static @NonNull String validateRemoteUrl(@NonNull CreateRepositoryRequest.Remote remote) {
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

	private static @NonNull List<String> normalizeMembers(@NonNull List<String> members) {
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

	private static @org.jspecify.annotations.Nullable String blankToNull(@org.jspecify.annotations.Nullable String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private @NonNull RepositoryEntity require(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.services.repositories().get(ctx.pathParam("name"));
		if (repository == null) {
			throw HttpError.notFound("No such repository: " + ctx.pathParam("name"));
		}
		return repository;
	}
	
	private @NonNull RepositoryEntity readable(@NonNull Context ctx) throws Exception {
		RepositoryEntity repository = this.require(ctx);
		this.services.auth().authorize(ctx, repository, AccessLevel.READ, "Basic realm=\"" + repository.name() + "\"");
		return repository;
	}
}
