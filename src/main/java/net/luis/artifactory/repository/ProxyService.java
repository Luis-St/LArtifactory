package net.luis.artifactory.repository;

import net.luis.artifactory.database.entity.RemoteConfigEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Fetches artifacts from the upstream of a remote (proxy) repository.<br>
 */
public class ProxyService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ProxyService.class);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

	private final HttpClient httpClient;
	private final long maxBytes;

	public ProxyService(long maxBytes) {
		this.maxBytes = maxBytes;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.proxy(ProxySelector.getDefault())
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	}

	/**
	 * Fetches a path from the upstream, returns null if it is not found upstream or the request fails.<br>
	 */
	public @Nullable Result fetch(@NonNull RemoteConfigEntity remote, @NonNull String path) {
		URI uri;
		try {
			uri = URI.create(remote.url() + "/" + encodePath(path));
		} catch (IllegalArgumentException e) {
			LOGGER.warn("Invalid upstream url for repository {}: {}", remote.repository(), remote.url(), e);
			return null;
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(REQUEST_TIMEOUT)
			.header("User-Agent", "LArtifactory-Proxy")
			.GET();
		if (remote.username() != null && !remote.username().isBlank()) {
			String credentials = remote.username() + ":" + (remote.password() == null ? "" : remote.password());
			builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
		}
		try {
			HttpResponse<byte[]> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				return null;
			}
			byte[] body = response.body();
			if (body.length > this.maxBytes) {
				LOGGER.warn("Upstream artifact {} from {} exceeds the max size ({} > {})", path, remote.repository(), body.length, this.maxBytes);
				return null;
			}
			String contentType = response.headers().firstValue("Content-Type").orElse(null);
			return new Result(body, contentType);
		} catch (Exception e) {
			LOGGER.warn("Failed to fetch {} from upstream of {} ({})", path, remote.repository(), remote.url(), e);
			return null;
		}
	}

	private static @NonNull String encodePath(@NonNull String path) {
		StringBuilder encoded = new StringBuilder();
		for (String segment : path.split("/")) {
			if (segment.isEmpty()) {
				continue;
			}
			if (encoded.length() > 0) {
				encoded.append('/');
			}
			encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
		}
		return encoded.toString();
	}

	public record Result(byte @NonNull [] body, @Nullable String contentType) {}
}
