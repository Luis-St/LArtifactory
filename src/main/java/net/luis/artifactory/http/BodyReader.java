package net.luis.artifactory.http;

import io.javalin.http.Context;
import net.luis.utils.function.throwable.ThrowableFunction;
import org.jspecify.annotations.NonNull;

import java.io.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Reads request bodies into memory with a size limit.<br>
 * <p>
 *     Most uploads are streamed to the blob store, only npm and Cargo publish requests have to be parsed in memory (base64 json, length prefixed binary).
 *     Their size is limited separately and the number of such bodies held at the same time is bounded by the heap size,
 *     so concurrent publishes can not exhaust the memory of the server.
 * </p>
 */
public class BodyReader {

	/**
	 * Maximum size of small json bodies (logins, tags, management requests).<br>
	 */
	public static final long MAX_JSON_BODY = 1024 * 1024;
	private static final long SLOT_WAIT_SECONDS = 30;

	private final long maxPackageBody;
	private final Semaphore packageSlots;

	/**
	 * @param maxPackageBody The maximum size of a publish request parsed in memory
	 * @param maxMemory The maximum heap size, a buffered body needs up to four times its size while it is parsed
	 */
	public BodyReader(long maxPackageBody, long maxMemory) {
		this.maxPackageBody = maxPackageBody;
		this.packageSlots = new Semaphore((int) Math.clamp(maxMemory / (4 * maxPackageBody), 1, 64));
	}

	public long maxPackageBody() {
		return this.maxPackageBody;
	}

	/**
	 * Reads a small json body.<br>
	 *
	 * @throws HttpError 413 if the body is larger than {@link #MAX_JSON_BODY}
	 */
	public byte @NonNull [] json(@NonNull Context ctx) throws IOException {
		return read(ctx, MAX_JSON_BODY);
	}

	/**
	 * Reads a publish request body and passes it to the action while holding one of the bounded package slots.<br>
	 *
	 * @throws HttpError 413 if the body is too large, 503 if no slot becomes available in time
	 */
	public <T> T withPackageBody(@NonNull Context ctx, @NonNull ThrowableFunction<byte[], T, Exception> action) throws Exception {
		if (ctx.req().getContentLengthLong() > this.maxPackageBody) {
			throw HttpError.payloadTooLarge();
		}
		if (!this.packageSlots.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS)) {
			throw HttpError.of(503, "Too many concurrent uploads, try again later");
		}
		try {
			return action.apply(read(ctx, this.maxPackageBody));
		} finally {
			this.packageSlots.release();
		}
	}

	private static byte @NonNull [] read(@NonNull Context ctx, long max) throws IOException {
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
}
