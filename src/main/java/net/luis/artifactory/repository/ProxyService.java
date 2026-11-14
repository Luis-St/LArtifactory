package net.luis.artifactory.repository;

import net.luis.artifactory.database.entity.RemoteConfigEntity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.*;

/**
 * Fetches artifacts from the upstream of a remote (proxy) repository.<br>
 */
public class ProxyService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(ProxyService.class);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
	/**
	 * The request timeout of the client ends once the headers arrived, a body that stalls for longer than this fails the read instead of
	 * blocking the request thread without limit.<br>
	 */
	private static final Duration BODY_IDLE_TIMEOUT = Duration.ofSeconds(60);
	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("upstream-watchdog").daemon().factory());
	
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
	
	@NonNull
	private static String encodePath(@NonNull String path) {
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
	
	/**
	 * Opens a path at the upstream, the body is streamed and has to be closed by the caller.<br>
	 * A response larger than the max size is rejected by its {@code Content-Length} before the download, a body without (or with a wrong) length
	 * fails with an {@link IOException} once it exceeds the max size, so a request never buffers more than a read buffer.<br>
	 *
	 * @return The response, null if the path is not found upstream, it is too large or the request fails
	 */
	@Nullable
	public Response open(@NonNull RemoteConfigEntity remote, @NonNull String path) {
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
			HttpResponse<InputStream> response = this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				response.body().close();
				return null;
			}
			long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
			if (length > this.maxBytes) {
				response.body().close();
				LOGGER.warn("Upstream artifact {} from {} exceeds the max size ({} > {})", path, remote.repository(), length, this.maxBytes);
				return null;
			}
			String contentType = response.headers().firstValue("Content-Type").orElse(null);
			return new Response(new LimitedInputStream(new IdleTimeoutInputStream(response.body(), BODY_IDLE_TIMEOUT), this.maxBytes, path), contentType, length);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			LOGGER.warn("Failed to fetch {} from upstream of {} ({})", path, remote.repository(), remote.url(), e);
			return null;
		}
	}
	
	/**
	 * A streamed upstream response.<br>
	 *
	 * @param contentLength The announced length of the body, -1 if unknown
	 */
	public record Response(@NonNull InputStream body, @Nullable String contentType, long contentLength) implements Closeable {
		
		@Override
		public void close() throws IOException {
			this.body.close();
		}
	}
	
	/**
	 * Fails the read once more than the limit was read.<br>
	 */
	private static final class LimitedInputStream extends FilterInputStream {
		
		private final long limit;
		private final String path;
		private long read;
		
		private LimitedInputStream(@NonNull InputStream input, long limit, @NonNull String path) {
			super(input);
			this.limit = limit;
			this.path = path;
		}
		
		private int count(int value) throws IOException {
			if (value > 0) {
				this.read += value;
				if (this.read > this.limit) {
					throw new IOException("Upstream artifact " + this.path + " exceeds the max size of " + this.limit + " bytes");
				}
			}
			return value;
		}
		
		@Override
		public int read() throws IOException {
			int value = super.read();
			if (value >= 0) {
				this.count(1);
			}
			return value;
		}
		
		@Override
		public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
			return this.count(super.read(buffer, offset, length));
		}
	}
	
	/**
	 * Closes the stream if a single read blocks for longer than the timeout, the blocked read then fails with an {@link IOException}.<br>
	 */
	private static final class IdleTimeoutInputStream extends FilterInputStream {
		
		private final long timeoutMillis;
		private volatile boolean timedOut;
		
		private IdleTimeoutInputStream(@NonNull InputStream input, @NonNull Duration timeout) {
			super(input);
			this.timeoutMillis = timeout.toMillis();
		}
		
		private <T> T guarded(@NonNull Callable<T> read) throws IOException {
			ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
				this.timedOut = true;
				try {
					this.in.close();
				} catch (IOException ignored) {}
			}, this.timeoutMillis, TimeUnit.MILLISECONDS);
			try {
				return read.call();
			} catch (IOException e) {
				throw this.timedOut ? new IOException("Upstream stopped sending data for " + this.timeoutMillis / 1000 + " seconds", e) : e;
			} catch (Exception e) {
				throw new IOException(e);
			} finally {
				watchdog.cancel(false);
			}
		}
		
		@Override
		public int read() throws IOException {
			return this.guarded(this.in::read);
		}
		
		@Override
		public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
			return this.guarded(() -> this.in.read(buffer, offset, length));
		}
	}
}
