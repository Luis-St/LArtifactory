package net.luis.artifactory.repository;

import net.luis.artifactory.database.entity.WebhookDeliveryEntity;
import net.luis.artifactory.database.entity.WebhookEntity;
import net.luis.artifactory.util.Json;
import net.luis.utils.io.database.Sql;
import net.luis.utils.io.database.SqlDatabase;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static net.luis.artifactory.database.Tables.*;

/**
 * Stores webhook subscriptions and delivers events to them.<br>
 * <p>
 *     Deliveries happen asynchronously on a small bounded thread pool so a slow or unreachable target never blocks the
 *     request that triggered the event. Each delivery is retried a few times and its final result is recorded. The
 *     payload is signed with HMAC-SHA256 when the webhook has a secret.<br>
 * </p>
 */
public class WebhookService {

	private static final Logger LOGGER = LoggerFactory.getLogger(WebhookService.class);
	private static final long CACHE_TTL_MILLIS = 30_000;
	private static final int MAX_ATTEMPTS = 3;
	private static final int MAX_DELIVERIES_PER_WEBHOOK = 100;
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

	//region Event types
	public static final String ARTIFACT_PUBLISHED = "artifact.published";
	public static final String ARTIFACT_DELETED = "artifact.deleted";
	public static final String PACKAGE_DELETED = "package.deleted";
	public static final String REPOSITORY_CREATED = "repository.created";
	public static final String REPOSITORY_DELETED = "repository.deleted";
	public static final String PING = "ping";
	public static final List<String> EVENTS = List.of(ARTIFACT_PUBLISHED, ARTIFACT_DELETED, PACKAGE_DELETED, REPOSITORY_CREATED, REPOSITORY_DELETED);
	//endregion

	private final SqlDatabase database;
	private final HttpClient httpClient;
	private final ExecutorService executor;

	private volatile List<WebhookEntity> activeCache = List.of();
	private volatile long cacheExpiry = 0;

	public WebhookService(@NonNull SqlDatabase database) {
		this.database = Objects.requireNonNull(database, "Database must not be null");
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.proxy(ProxySelector.getDefault())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		this.executor = new ThreadPoolExecutor(1, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(2000), runnable -> {
			Thread thread = new Thread(runnable, "webhook-delivery");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.DiscardPolicy());
	}

	//region CRUD
	public @NonNull List<WebhookEntity> list(@Nullable String repository) throws SqlException {
		var query = this.database.from(WEBHOOKS).select();
		if (repository != null && !repository.isBlank()) {
			query = query.where(Sql.equalTo(WEBHOOK_REPOSITORY, repository));
		}
		return query.orderBy(WEBHOOK_CREATED_AT.ascending()).fetch();
	}

	public @Nullable WebhookEntity get(@NonNull String id) throws SqlException {
		return this.database.from(WEBHOOKS).select().where(Sql.equalTo(WEBHOOK_ID, id)).fetchOneOrNull();
	}

	public @NonNull WebhookEntity create(@Nullable String repository, @NonNull String url, @Nullable String secret, @Nullable String events, boolean active, @Nullable String createdBy) throws SqlException {
		WebhookEntity webhook = new WebhookEntity(UUID.randomUUID().toString(), repository, url, secret, events, active, Instant.now(), createdBy);
		this.database.from(WEBHOOKS).insert(webhook).execute();
		this.invalidate();
		return webhook;
	}

	public void update(@NonNull String id, @Nullable String url, @Nullable String events, @Nullable Boolean active) throws SqlException {
		if (url != null) {
			this.database.from(WEBHOOKS).update().set(WEBHOOK_URL, url).where(Sql.equalTo(WEBHOOK_ID, id)).execute();
		}
		if (events != null) {
			this.database.from(WEBHOOKS).update().set(WEBHOOK_EVENTS, events.isBlank() ? null : events).where(Sql.equalTo(WEBHOOK_ID, id)).execute();
		}
		if (active != null) {
			this.database.from(WEBHOOKS).update().set(WEBHOOK_ACTIVE, active).where(Sql.equalTo(WEBHOOK_ID, id)).execute();
		}
		this.invalidate();
	}

	public int delete(@NonNull String id) throws SqlException {
		this.database.from(WEBHOOK_DELIVERIES).delete().where(Sql.equalTo(DELIVERY_WEBHOOK, id)).execute();
		int removed = this.database.from(WEBHOOKS).delete().where(Sql.equalTo(WEBHOOK_ID, id)).execute();
		this.invalidate();
		return removed;
	}

	public @NonNull List<WebhookDeliveryEntity> deliveries(@NonNull String webhookId, int limit) throws SqlException {
		List<WebhookDeliveryEntity> ordered = new ArrayList<>(this.database.from(WEBHOOK_DELIVERIES).select()
			.where(Sql.equalTo(DELIVERY_WEBHOOK, webhookId))
			.orderBy(DELIVERY_CREATED_AT.ascending())
			.fetch());
		Collections.reverse(ordered);
		return ordered.stream().limit(Math.max(1, limit)).toList();
	}
	//endregion

	//region Dispatch
	private void invalidate() {
		this.cacheExpiry = 0;
	}

	private @NonNull List<WebhookEntity> activeWebhooks() throws SqlException {
		if (System.currentTimeMillis() < this.cacheExpiry) {
			return this.activeCache;
		}
		List<WebhookEntity> active = this.database.from(WEBHOOKS).select().where(Sql.equalTo(WEBHOOK_ACTIVE, true)).fetch();
		this.activeCache = active;
		this.cacheExpiry = System.currentTimeMillis() + CACHE_TTL_MILLIS;
		return active;
	}

	private static boolean subscribed(@Nullable String events, @NonNull String event) {
		if (events == null || events.isBlank()) {
			return true;
		}
		for (String entry : events.split("[,\\s]+")) {
			if (entry.strip().equalsIgnoreCase(event)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Dispatches an event to every matching active webhook. Never throws: any failure is logged and swallowed so the
	 * triggering request is unaffected.<br>
	 */
	public void dispatch(@NonNull String event, @Nullable String repository, @Nullable String target) {
		try {
			List<WebhookEntity> matching = new ArrayList<>();
			for (WebhookEntity webhook : this.activeWebhooks()) {
				boolean repositoryMatches = webhook.repository() == null || webhook.repository().equals(repository);
				if (repositoryMatches && subscribed(webhook.events(), event)) {
					matching.add(webhook);
				}
			}
			if (matching.isEmpty()) {
				return;
			}
			byte[] payload = payload(event, repository, target).getBytes(StandardCharsets.UTF_8);
			for (WebhookEntity webhook : matching) {
				this.executor.submit(() -> this.deliverWithRetries(webhook, event, repository, payload));
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to dispatch webhook event {} for repository {}", event, repository, e);
		}
	}

	private static @NonNull String payload(@NonNull String event, @Nullable String repository, @Nullable String target) {
		ObjectNode node = Json.object();
		node.put("event", event);
		node.put("repository", repository);
		node.put("target", target);
		node.put("timestamp", Instant.now().toString());
		return Json.write(node);
	}

	private void deliverWithRetries(@NonNull WebhookEntity webhook, @NonNull String event, @Nullable String repository, byte @NonNull [] payload) {
		int statusCode = 0;
		String error = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				HttpResponse<Void> response = this.send(webhook, event, payload);
				statusCode = response.statusCode();
				if (statusCode >= 200 && statusCode < 300) {
					this.recordDelivery(webhook.id(), event, repository, true, statusCode, null);
					return;
				}
				error = "HTTP " + statusCode;
			} catch (Exception e) {
				error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			}
			if (attempt < MAX_ATTEMPTS) {
				try {
					Thread.sleep(1000L * attempt);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		this.recordDelivery(webhook.id(), event, repository, false, statusCode, error);
	}

	private @NonNull HttpResponse<Void> send(@NonNull WebhookEntity webhook, @NonNull String event, byte @NonNull [] payload) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(webhook.url()))
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.header("User-Agent", "LArtifactory-Webhook")
			.header("X-LArtifactory-Event", event)
			.header("X-LArtifactory-Delivery", UUID.randomUUID().toString())
			.POST(HttpRequest.BodyPublishers.ofByteArray(payload));
		if (webhook.secret() != null && !webhook.secret().isBlank()) {
			builder.header("X-LArtifactory-Signature", "sha256=" + sign(webhook.secret(), payload));
		}
		return this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
	}

	private static @NonNull String sign(@NonNull String secret, byte @NonNull [] payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload));
		} catch (Exception e) {
			throw new IllegalStateException("Failed to sign webhook payload", e);
		}
	}

	private void recordDelivery(@NonNull String webhookId, @NonNull String event, @Nullable String repository, boolean success, long statusCode, @Nullable String error) {
		try {
			WebhookDeliveryEntity delivery = new WebhookDeliveryEntity(UUID.randomUUID().toString(), webhookId, event, repository, success, statusCode, truncate(error), Instant.now());
			this.database.from(WEBHOOK_DELIVERIES).insert(delivery).execute();
			this.prune(webhookId);
		} catch (Exception e) {
			LOGGER.warn("Failed to record webhook delivery for {}", webhookId, e);
		}
	}

	private void prune(@NonNull String webhookId) throws SqlException {
		List<WebhookDeliveryEntity> all = this.database.from(WEBHOOK_DELIVERIES).select()
			.where(Sql.equalTo(DELIVERY_WEBHOOK, webhookId))
			.orderBy(DELIVERY_CREATED_AT.ascending())
			.fetch();
		int excess = all.size() - MAX_DELIVERIES_PER_WEBHOOK;
		for (int i = 0; i < excess; i++) {
			this.database.from(WEBHOOK_DELIVERIES).delete().where(Sql.equalTo(DELIVERY_ID, all.get(i).id())).execute();
		}
	}

	/**
	 * Sends a synchronous {@code ping} delivery (one attempt) to a webhook and records and returns the result.<br>
	 */
	public @NonNull WebhookDeliveryEntity test(@NonNull WebhookEntity webhook) {
		byte[] payload = payload(PING, webhook.repository(), null).getBytes(StandardCharsets.UTF_8);
		boolean success = false;
		long statusCode = 0;
		String error = null;
		try {
			HttpResponse<Void> response = this.send(webhook, PING, payload);
			statusCode = response.statusCode();
			success = statusCode >= 200 && statusCode < 300;
			if (!success) {
				error = "HTTP " + statusCode;
			}
		} catch (Exception e) {
			error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
		}
		WebhookDeliveryEntity delivery = new WebhookDeliveryEntity(UUID.randomUUID().toString(), webhook.id(), PING, webhook.repository(), success, statusCode, truncate(error), Instant.now());
		try {
			this.database.from(WEBHOOK_DELIVERIES).insert(delivery).execute();
			this.prune(webhook.id());
		} catch (Exception e) {
			LOGGER.warn("Failed to record webhook test delivery for {}", webhook.id(), e);
		}
		return delivery;
	}

	private static @Nullable String truncate(@Nullable String value) {
		if (value == null) {
			return null;
		}
		return value.length() > 1024 ? value.substring(0, 1024) : value;
	}
	//endregion

	public void close() {
		this.executor.shutdown();
		try {
			if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
				this.executor.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.executor.shutdownNow();
		}
	}
}
