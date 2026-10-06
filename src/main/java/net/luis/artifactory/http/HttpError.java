package net.luis.artifactory.http;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

public class HttpError extends RuntimeException {
	
	private final int status;
	private final Map<String, String> headers;
	private final @Nullable String contentType;
	private final @Nullable String body;
	
	private HttpError(int status, @NonNull String message, @Nullable String contentType, @Nullable String body, @NonNull Map<String, String> headers) {
		super(message);
		this.status = status;
		this.contentType = contentType;
		this.body = body;
		this.headers = Map.copyOf(headers);
	}
	
	public static @NonNull HttpError of(int status, @NonNull String message) {
		return new HttpError(status, message, "text/plain", message, Map.of());
	}
	
	public static @NonNull HttpError json(int status, @NonNull String message, @NonNull String json) {
		return new HttpError(status, message, "application/json", json, Map.of());
	}
	
	public static @NonNull HttpError empty(int status) {
		return new HttpError(status, "HTTP " + status, null, null, Map.of());
	}
	
	public static @NonNull HttpError badRequest(@NonNull String message) {
		return of(400, message);
	}
	
	public static @NonNull HttpError notFound() {
		return of(404, "No such package or version");
	}
	
	public static @NonNull HttpError notFound(@NonNull String message) {
		return of(404, message);
	}
	
	public static @NonNull HttpError conflict(@NonNull String message) {
		return of(409, message);
	}
	
	public static @NonNull HttpError forbidden(@NonNull String message) {
		return of(403, message);
	}
	
	public static @NonNull HttpError unauthorized(@NonNull String challenge) {
		return new HttpError(401, "Unauthorized", "text/plain", "Authentication required", Map.of("WWW-Authenticate", challenge));
	}
	
	public static @NonNull HttpError payloadTooLarge() {
		return of(413, "Artifact exceeds configured max upload size");
	}
	
	public static @NonNull HttpError methodNotAllowed() {
		return of(405, "Method not allowed");
	}
	
	public int status() {
		return this.status;
	}
	
	public @NonNull Map<String, String> headers() {
		return this.headers;
	}
	
	public @Nullable String contentType() {
		return this.contentType;
	}
	
	public @Nullable String body() {
		return this.body;
	}
}
