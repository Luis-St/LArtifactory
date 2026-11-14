package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.dto.response.AuditResponse;
import net.luis.artifactory.dto.response.ErrorResponse;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.repository.AuditService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Read only access to the audit log (administrators only).<br>
 */
public class AuditAdminHandler {
	
	private final Services services;
	
	public AuditAdminHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
	}
	
	private static long parseLong(@NonNull Context ctx, @NonNull String name, long defaultValue, long max) {
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
	
	private static @Nullable Instant parseInstant(@Nullable String value, @NonNull String name) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Instant.parse(value.strip());
		} catch (DateTimeParseException e) {
			throw HttpError.badRequest("Invalid " + name + " timestamp, expected ISO-8601 like 2026-11-01T00:00:00Z");
		}
	}
	
	@OpenApi(
		summary = "List audit log entries (newest first), administrators only",
		operationId = "listAudit",
		path = "/api/audit",
		methods = HttpMethod.GET,
		tags = "Audit",
		queryParams = {
			@OpenApiParam(name = "repository", description = "Filter by repository"),
			@OpenApiParam(name = "actor", description = "Filter by the user that performed the action"),
			@OpenApiParam(name = "action", description = "Filter by action identifier, e.g. repository.create"),
			@OpenApiParam(name = "from", description = "Only entries at or after this ISO-8601 instant, e.g. 2026-11-01T00:00:00Z"),
			@OpenApiParam(name = "to", description = "Only entries before this ISO-8601 instant"),
			@OpenApiParam(name = "limit", description = "Maximum number of entries (default 100, max 500)"),
			@OpenApiParam(name = "offset", description = "Number of entries to skip for paging")
		},
		responses = {
			@OpenApiResponse(
				status = "200",
				content = @OpenApiContent(from = AuditResponse[].class),
				headers = @OpenApiParam(name = RepositoryAdminHandler.TOTAL_COUNT_HEADER, type = Long.class, description = "Total number of matching entries")
			),
			@OpenApiResponse(status = "400", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "401", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "403", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void list(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		int limit = (int) parseLong(ctx, "limit", AuditService.DEFAULT_LIMIT, AuditService.MAX_LIMIT);
		long offset = parseLong(ctx, "offset", 0, Long.MAX_VALUE);
		Instant from = parseInstant(ctx.queryParam("from"), "from");
		Instant to = parseInstant(ctx.queryParam("to"), "to");
		String repository = ctx.queryParam("repository");
		String actor = ctx.queryParam("actor");
		String action = ctx.queryParam("action");
		ctx.header(RepositoryAdminHandler.TOTAL_COUNT_HEADER, String.valueOf(this.services.audit().count(repository, actor, action, from, to)));
		ctx.json(this.services.audit().list(repository, actor, action, from, to, limit, offset).stream().map(AuditResponse::of).toList());
	}
}
