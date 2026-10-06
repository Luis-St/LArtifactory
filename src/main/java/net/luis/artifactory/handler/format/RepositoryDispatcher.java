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
	
	private final Services services;
	private final Map<RepositoryType, FormatHandler> handlers = new EnumMap<>(RepositoryType.class);
	
	public RepositoryDispatcher(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
		for (FormatHandler handler : List.of(new MavenHandler(services), new GenericHandler(services), new PypiHandler(services), new NpmHandler(services), new NugetHandler(services), new CargoHandler(services))) {
			this.handlers.put(handler.type(), handler);
		}
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
