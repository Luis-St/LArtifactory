package net.luis.artifactory.http;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

public class HttpError extends RuntimeException {
	
	private final int status;
	private final Map<String, String> headers;
	@Nullable private final String contentType;
	@Nullable private final String body;
	
	private HttpError(int status, @NonNull String message, @Nullable String contentType, @Nullable String body, @NonNull Map<String, String> headers) {
		super(message);
		this.status = status;
		this.contentType = contentType;
		this.body = body;
		this.headers = Map.copyOf(headers);
	}
	
	@NonNull
	public static HttpError of(int status, @NonNull String message) {
		return new HttpError(status, message, "text/plain", message, Map.of());
	}
	
	@NonNull
	public static HttpError json(int status, @NonNull String message, @NonNull String json) {
		return new HttpError(status, message, "application/json", json, Map.of());
	}
	
	@NonNull
	public static HttpError json(int status, @NonNull String message, @NonNull String json, @NonNull Map<String, String> headers) {
		return new HttpError(status, message, "application/json", json, headers);
	}
	
	@NonNull
	public static HttpError empty(int status) {
		return new HttpError(status, "HTTP " + status, null, null, Map.of());
	}
	
	@NonNull
	public static HttpError badRequest(@NonNull String message) {
		return of(400, message);
	}
	
	@NonNull
	public static HttpError notFound() {
		return of(404, "No such package or version");
	}
	
	@NonNull
	public static HttpError notFound(@NonNull String message) {
		return of(404, message);
	}
	
	@NonNull
	public static HttpError conflict(@NonNull String message) {
		return of(409, message);
	}
	
	@NonNull
	public static HttpError forbidden(@NonNull String message) {
		return of(403, message);
	}
	
	@NonNull
	public static HttpError unauthorized(@NonNull String challenge) {
		return new HttpError(401, "Unauthorized", "text/plain", "Authentication required", Map.of("WWW-Authenticate", challenge));
	}
	
	@NonNull
	public static HttpError payloadTooLarge() {
		return of(413, "Artifact exceeds configured max upload size");
	}
	
	@NonNull
	public static HttpError tooManyRequests(long retryAfterSeconds) {
		return new HttpError(429, "Too many failed logins", "text/plain", "Too many failed logins, try again later", Map.of("Retry-After", String.valueOf(retryAfterSeconds)));
	}
	
	@NonNull
	public static HttpError methodNotAllowed() {
		return of(405, "Method not allowed");
	}
	
	@NonNull
	public static HttpError rateLimited(long retryAfterSeconds) {
		return new HttpError(429, "Rate limit exceeded", "text/plain", "Rate limit exceeded", Map.of("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds))));
	}
	
	public int status() {
		return this.status;
	}
	
	@NonNull
	public Map<String, String> headers() {
		return this.headers;
	}
	
	@Nullable
	public String contentType() {
		return this.contentType;
	}
	
	@Nullable
	public String body() {
		return this.body;
	}
}
