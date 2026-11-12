package net.luis.artifactory.handler.admin;

import io.javalin.http.Context;
import io.javalin.openapi.*;
import net.luis.artifactory.Services;
import net.luis.artifactory.database.entity.WebhookEntity;
import net.luis.artifactory.dto.request.CreateWebhookRequest;
import net.luis.artifactory.dto.request.UpdateWebhookRequest;
import net.luis.artifactory.dto.response.ErrorResponse;
import net.luis.artifactory.dto.response.WebhookDeliveryResponse;
import net.luis.artifactory.dto.response.WebhookResponse;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.repository.WebhookService;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Management of webhooks (administrators only).<br>
 */
public class WebhookAdminHandler {

	private final Services services;

	public WebhookAdminHandler(@NonNull Services services) {
		this.services = Objects.requireNonNull(services, "Services must not be null");
	}

	@OpenApi(
		summary = "List webhooks",
		operationId = "listWebhooks",
		path = "/api/webhooks",
		methods = HttpMethod.GET,
		tags = "Webhooks",
		queryParams = @OpenApiParam(name = "repository", description = "Only webhooks scoped to this repository"),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = WebhookResponse[].class))
	)
	public void list(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		ctx.json(this.services.webhooks().list(ctx.queryParam("repository")).stream().map(WebhookResponse::of).toList());
	}

	@OpenApi(
		summary = "Create a webhook",
		description = "Events: artifact.published, artifact.deleted, package.deleted, repository.created, repository.deleted (empty = all)",
		operationId = "createWebhook",
		path = "/api/webhooks",
		methods = HttpMethod.POST,
		tags = "Webhooks",
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = CreateWebhookRequest.class)),
		responses = {
			@OpenApiResponse(status = "201", content = @OpenApiContent(from = WebhookResponse.class)),
			@OpenApiResponse(status = "400", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void create(@NonNull Context ctx) throws Exception {
		var principal = this.services.auth().requireAdmin(ctx);
		CreateWebhookRequest request = ctx.bodyAsClass(CreateWebhookRequest.class);
		String url = validateUrl(request.url());
		String repository = this.validateRepository(request.repository());
		String events = validateEvents(request.events());
		String secret = request.secret() == null || request.secret().isBlank() ? null : request.secret().strip();
		if (secret != null && secret.length() > 256) {
			throw HttpError.badRequest("Secret must have at most 256 characters");
		}
		WebhookEntity webhook = this.services.webhooks().create(repository, url, secret, events, request.active() == null || request.active(), principal.username());
		ctx.status(201).json(WebhookResponse.of(webhook));
	}

	@OpenApi(
		summary = "Get a webhook",
		operationId = "getWebhook",
		path = "/api/webhooks/{id}",
		methods = HttpMethod.GET,
		tags = "Webhooks",
		pathParams = @OpenApiParam(name = "id", required = true),
		responses = {
			@OpenApiResponse(status = "200", content = @OpenApiContent(from = WebhookResponse.class)),
			@OpenApiResponse(status = "404", content = @OpenApiContent(from = ErrorResponse.class))
		}
	)
	public void get(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		ctx.json(WebhookResponse.of(this.require(ctx)));
	}

	@OpenApi(
		summary = "Update a webhook",
		operationId = "updateWebhook",
		path = "/api/webhooks/{id}",
		methods = HttpMethod.PATCH,
		tags = "Webhooks",
		pathParams = @OpenApiParam(name = "id", required = true),
		requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = UpdateWebhookRequest.class)),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = WebhookResponse.class))
	)
	public void update(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		WebhookEntity webhook = this.require(ctx);
		UpdateWebhookRequest request = ctx.bodyAsClass(UpdateWebhookRequest.class);
		String url = request.url() == null ? null : validateUrl(request.url());
		String events = request.events() == null ? null : Objects.requireNonNullElse(validateEvents(request.events()), "");
		this.services.webhooks().update(webhook.id(), url, events, request.active());
		ctx.json(WebhookResponse.of(Objects.requireNonNull(this.services.webhooks().get(webhook.id()))));
	}

	@OpenApi(
		summary = "Delete a webhook",
		operationId = "deleteWebhook",
		path = "/api/webhooks/{id}",
		methods = HttpMethod.DELETE,
		tags = "Webhooks",
		pathParams = @OpenApiParam(name = "id", required = true),
		responses = @OpenApiResponse(status = "204")
	)
	public void delete(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		WebhookEntity webhook = this.require(ctx);
		this.services.webhooks().delete(webhook.id());
		ctx.status(204);
	}

	@OpenApi(
		summary = "Send a test ping to a webhook and return the delivery result",
		operationId = "testWebhook",
		path = "/api/webhooks/{id}/test",
		methods = HttpMethod.POST,
		tags = "Webhooks",
		pathParams = @OpenApiParam(name = "id", required = true),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = WebhookDeliveryResponse.class))
	)
	public void test(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		WebhookEntity webhook = this.require(ctx);
		ctx.json(WebhookDeliveryResponse.of(this.services.webhooks().test(webhook)));
	}

	@OpenApi(
		summary = "List the recent delivery attempts of a webhook (newest first)",
		operationId = "listWebhookDeliveries",
		path = "/api/webhooks/{id}/deliveries",
		methods = HttpMethod.GET,
		tags = "Webhooks",
		pathParams = @OpenApiParam(name = "id", required = true),
		responses = @OpenApiResponse(status = "200", content = @OpenApiContent(from = WebhookDeliveryResponse[].class))
	)
	public void deliveries(@NonNull Context ctx) throws Exception {
		this.services.auth().requireAdmin(ctx);
		WebhookEntity webhook = this.require(ctx);
		ctx.json(this.services.webhooks().deliveries(webhook.id(), 50).stream().map(WebhookDeliveryResponse::of).toList());
	}

	private @NonNull WebhookEntity require(@NonNull Context ctx) throws Exception {
		WebhookEntity webhook = this.services.webhooks().get(ctx.pathParam("id"));
		if (webhook == null) {
			throw HttpError.notFound("No such webhook");
		}
		return webhook;
	}

	private static @NonNull String validateUrl(@Nullable String url) {
		if (url == null || url.isBlank()) {
			throw HttpError.badRequest("url is required");
		}
		String trimmed = url.strip();
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
			throw HttpError.badRequest("url must be an http or https url");
		}
		if (trimmed.length() > 1024) {
			throw HttpError.badRequest("url must have at most 1024 characters");
		}
		return trimmed;
	}

	private @Nullable String validateRepository(@Nullable String repository) throws Exception {
		if (repository == null || repository.isBlank()) {
			return null;
		}
		if (this.services.repositories().get(repository) == null) {
			throw HttpError.notFound("No such repository: " + repository);
		}
		return repository;
	}

	private static @Nullable String validateEvents(@Nullable List<String> events) {
		if (events == null || events.isEmpty()) {
			return null;
		}
		StringBuilder joined = new StringBuilder();
		for (String event : events) {
			if (event == null || event.isBlank()) {
				continue;
			}
			String normalized = event.strip().toLowerCase(Locale.ROOT);
			if (!WebhookService.EVENTS.contains(normalized)) {
				throw HttpError.badRequest("Unknown event '" + event + "', allowed: " + WebhookService.EVENTS);
			}
			joined.append(joined.isEmpty() ? "" : ",").append(normalized);
		}
		return joined.isEmpty() ? null : joined.toString();
	}
}
