package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.dto.response.AuditResponse;
import net.luis.artifactory.dto.response.ErrorResponse;
import net.luis.artifactory.repository.AuditService;
import org.jspecify.annotations.NonNull;

import java.util.Objects;

/**
 * Read only access to the audit log (administrators only).<br>
 */
public class AuditAdminHandler {

	private final Services services;

	public AuditAdminHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
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
			@OpenApiParam(name = "limit", description = "Maximum number of entries (default 100, max 500)"),
			@OpenApiParam(name = "offset", description = "Number of entries to skip for paging")
		},
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = AuditResponse[].class)),
			@OpenApiResponse(status = "401", content = @OpenApiContent(from = ErrorResponse.class)),
			@OpenApiResponse(status = "403", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void list(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		int limit = parseInt(ctx.queryParam("limit"), AuditService.DEFAULT_LIMIT);
		int offset = parseInt(ctx.queryParam("offset"), 0);
		ctx.json(this.services.audit().list(ctx.queryParam("repository"), ctx.queryParam("actor"), ctx.queryParam("action"), limit, offset)
			.stream().map(AuditResponse::of).toList());
	}

	private static int parseInt(String value, int defaultValue) {
		if (value == null || value.isBlank()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(value.strip());
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}
}
