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
import net.luis.artifactory.repository.RepositoryService;
import net.luis.artifactory.repository.RepositoryType;
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
				result.add(RepositoryResponse.of(repository, baseUrl));
			}
		}
		ctx.json(result);
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
		ctx.json(RepositoryResponse.of(repository, Requests.baseUrl(ctx, this.services.config())));
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
		this.services.auth().requireAdmin(ctx);
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
		
		RepositoryEntity repository = this.services.repositories().create(request.name(), type, request.description(), Boolean.TRUE.equals(request.publicRead()), Boolean.TRUE.equals(request.allowRedeploy()));
		ctx.status(201).json(RepositoryResponse.of(repository, Requests.baseUrl(ctx, this.services.config())));
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
		this.services.auth().requireAdmin(ctx);
		RepositoryEntity repository = this.require(ctx);
		UpdateRepositoryRequest request = ctx.bodyAsClass(UpdateRepositoryRequest.class);
		this.services.repositories().update(repository.name(), request.description(), request.publicRead(), request.allowRedeploy());
		ctx.json(RepositoryResponse.of(Objects.requireNonNull(this.services.repositories().get(repository.name())), Requests.baseUrl(ctx, this.services.config())));
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
		this.services.auth().requireAdmin(ctx);
		RepositoryEntity repository = this.require(ctx);
		this.services.repositories().delete(repository.name());
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
		this.services.auth().authorize(ctx, repository, AccessLevel.DELETE, "Basic realm=\"" + repository.name() + "\"");
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
