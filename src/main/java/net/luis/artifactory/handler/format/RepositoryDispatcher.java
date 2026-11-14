package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.database.entity.RepositoryEntity;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.RepositoryType;
import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * Dispatches requests to {@code /{type}/{repository}/...} to the {@link FormatHandler} of the repository type.<br>
 */
public class RepositoryDispatcher {
	
	/**
	 * First segments of the management api, only these are taken from a mapped host so that e.g. a Maven group {@code api.example} stays reachable.<br>
	 */
	private static final Set<String> API_RESOURCES = Set.of("me", "session", "repositories", "users", "permissions", "tokens", "audit", "webhooks", "summary", "packages", "files", "search", "downloads");
	private static final Set<String> HEALTH_PATHS = Set.of("/health", "/health/live", "/health/ready");
	private static final Set<String> API_DOCS_SEGMENTS = Set.of("swagger", "openapi", "webjars");
	public static final String HOST_MAPPED_ATTRIBUTE = "artifactory.hostMapped";
	private final Services services;
	private final Map<RepositoryType, FormatHandler> handlers = new EnumMap<>(RepositoryType.class);
	
	public RepositoryDispatcher(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
		for (FormatHandler handler : List.of(new MavenHandler(services), new GenericHandler(services), new PypiHandler(services), new NpmHandler(services), new NugetHandler(services), new CargoHandler(services), new OciHandler(services))) {
			this.handlers.put(handler.type(), handler);
		}
	}
	
	/**
	 * Serves requests to hosts mapped to a repository ({@code ARTIFACTORY_HOST_REPOSITORIES}) at the root path.<br>
	 * Registered as before handler, the endpoint handlers are skipped if the request was handled.<br>
	 */
	public void handleMappedHost(@NonNull Context ctx) throws Exception {
		Map<String, String> mapping = this.services.config().hostRepositories();
		if (mapping.isEmpty()) {
			return;
		}
		String forwardedHost = this.services.config().trustProxy() ? ctx.header("X-Forwarded-Host") : null;
		String host = Objects.requireNonNullElse(forwardedHost, Objects.requireNonNullElse(ctx.header("Host"), ""));
		host = host.split(",")[0].strip().toLowerCase(Locale.ROOT);
		int colon = host.lastIndexOf(':');
		if (colon > 0 && !host.endsWith("]")) {
			host = host.substring(0, colon);
		}
		String name = mapping.get(host);
		String rawPath = Requests.rawPath(ctx);
		if (name == null || this.isReserved(rawPath)) {
			return;
		}
		
		RepositoryEntity repository = this.services.repositories().get(name);
		RepositoryType type = repository == null ? null : RepositoryType.byId(repository.type());
		if (type == null) {
			throw HttpError.notFound("No such repository: " + name);
		}
		ctx.attribute(HOST_MAPPED_ATTRIBUTE, true);
		ctx.skipRemainingHandlers();
		this.handlers.get(type).handle(ctx, repository, rawPath.startsWith("/") ? rawPath.substring(1) : rawPath);
	}
	
	/**
	 * Checks whether a path of a mapped host belongs to the server instead of the repository.<br>
	 * Paths are matched by whole segments, so packages such as {@code healthcheck}, {@code swagger-ui} or {@code ui-kit} are not hidden.<br>
	 */
	private boolean isReserved(@NonNull String rawPath) {
		if (HEALTH_PATHS.contains(rawPath)) {
			return true;
		}
		List<String> segments = Arrays.stream(rawPath.split("/")).filter(segment -> !segment.isEmpty()).toList();
		if (segments.isEmpty()) {
			return false;
		}
		String first = segments.getFirst();
		if ("ui".equals(first)) {
			return true;
		}
		if ("api".equals(first)) {
			return segments.size() >= 2 && API_RESOURCES.contains(segments.get(1));
		}
		return this.services.config().apiDocsEnabled() && API_DOCS_SEGMENTS.stream().anyMatch(segment -> first.equals(segment) || first.startsWith(segment + "."));
	}
	
	/**
	 * Handles the OCI distribution api at the root of the host ({@code /v2/...}), the only location Docker supports.<br>
	 * The first component of the image name selects the repository: {@code docker push host/{repository}/{image}:{tag}}.<br>
	 */
	public void handleRegistry(@NonNull Context ctx) throws Exception {
		OciHandler oci = (OciHandler) this.handlers.get(RepositoryType.OCI);
		try {
			this.handleRegistry(ctx, oci);
		} catch (HttpError e) {
			throw oci.translate(ctx, e, null);
		}
	}
	
	private void handleRegistry(@NonNull Context ctx, @NonNull OciHandler oci) throws Exception {
		String rawPath = Requests.rawPath(ctx);
		String rest = rawPath.length() > "/v2/".length() ? rawPath.substring("/v2/".length()) : "";
		List<String> segments = Requests.decodedSegments(rest);
		if (segments.isEmpty()) {
			oci.ping(ctx);
			return;
		}
		// A repository named token is still reachable, its endpoints have at least an image and an endpoint segment
		if (segments.equals(List.of("token"))) {
			oci.token(ctx);
			return;
		}
		if (segments.equals(List.of("_catalog"))) {
			oci.catalog(ctx);
			return;
		}
		String[] parts = rest.split("/", 2);
		String name = Requests.decode(parts[0]);
		RepositoryEntity repository = this.services.repositories().get(name);
		if (repository == null || !RepositoryType.OCI.id().equals(repository.type())) {
			ctx.header(OciHandler.API_VERSION_HEADER, OciHandler.API_VERSION);
			throw OciHandler.error(404, "NAME_UNKNOWN", "No such OCI repository: " + name);
		}
		ctx.attribute(OciHandler.ROOT_ATTRIBUTE, true);
		oci.handle(ctx, repository, "v2/" + (parts.length == 2 ? parts[1] : ""));
	}
	
	public void handle(@NonNull Context ctx) throws Exception {
		String rawPath = Requests.rawPath(ctx);
		String trimmed = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
		
		String[] parts = trimmed.split("/", 3);
		if (parts.length < 2 || parts[1].isEmpty()) {
			throw HttpError.notFound("No such repository");
		}
		RepositoryType type = RepositoryType.byId(parts[0]);
		String name = Requests.decode(parts[1]);
		RepositoryEntity repository = this.services.repositories().get(name);
		if (type == null || repository == null || !repository.type().equals(type.id())) {
			throw HttpError.notFound("No such repository: " + name);
		}
		
		String subPath = parts.length == 3 ? parts[2] : "";
		this.handlers.get(type).handle(ctx, repository, subPath);
	}
}
