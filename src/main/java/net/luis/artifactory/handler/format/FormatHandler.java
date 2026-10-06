package net.luis.artifactory.handler.format;

import io.javalin.http.Context;
import net.luis.artifactory.Services;
import net.luis.artifactory.auth.AccessLevel;
import net.luis.artifactory.auth.Principal;
import net.luis.artifactory.database.entity.RepositoryEntity;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.http.Requests;
import net.luis.artifactory.repository.RepositoryType;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.util.Objects;

/**
 * Base class of the handlers implementing the wire protocol of a package ecosystem.<br>
 * All requests to {@code /{type}/{repository}/...} are dispatched to the handler of the repository type.<br>
 */
public abstract class FormatHandler {
	
	protected final Services services;
	
	protected FormatHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
	}
	
	public abstract @NonNull RepositoryType type();
	
	/**
	 * Handles a request to the repository.<br>
	 *
	 * @param ctx The request context
	 * @param repository The repository addressed by the request
	 * @param path The raw (percent-encoded) path below the repository without leading slash, may be empty
	 */
	public abstract void handle(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull String path) throws Exception;
	
	protected @NonNull String challenge(@NonNull RepositoryEntity repository) {
		return "Basic realm=\"" + repository.name() + "\"";
	}
	
	protected @Nullable Principal authorize(@NonNull Context ctx, @NonNull RepositoryEntity repository, @NonNull AccessLevel level) throws SqlException {
		return this.services.auth().authorize(ctx, repository, level, this.challenge(repository));
	}
	
	protected static @Nullable String username(@Nullable Principal principal) {
		return principal == null ? null : principal.username();
	}
	
	protected @NonNull String repositoryUrl(@NonNull Context ctx, @NonNull RepositoryEntity repository) {
		return Requests.baseUrl(ctx, this.services.config()) + "/" + this.type().id() + "/" + repository.name();
	}
	
	/**
	 * Reads the request body into memory, limited to the configured max upload size.<br>
	 */
	protected byte @NonNull [] readBody(@NonNull Context ctx) throws IOException {
		long max = this.services.config().maxUploadSize() * 2;
		if (ctx.req().getContentLengthLong() > max) {
			throw HttpError.payloadTooLarge();
		}
		try (InputStream input = ctx.bodyInputStream()) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > max) {
					throw HttpError.payloadTooLarge();
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}
	
	protected static void text(@NonNull Context ctx, int status, @NonNull String body) {
		ctx.status(status).contentType("text/plain; charset=utf-8").result(body);
	}
	
	protected static void json(@NonNull Context ctx, int status, @NonNull Object body) {
		ctx.status(status).contentType("application/json").result(net.luis.artifactory.util.Json.write(body));
	}
}
