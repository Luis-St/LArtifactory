package net.luis.artifactory.http;

import io.javalin.http.Context;
import net.luis.artifactory.config.ServerConfig;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Requests {
	
	private Requests() {}
	
	public static @NonNull String baseUrl(@NonNull Context ctx, @NonNull ServerConfig config) {
		if (config.baseUrl() != null) {
			return config.baseUrl();
		}

		// Forwarded headers are client-controlled; honor them only when the deployment runs behind a trusted proxy.
		String proto = config.trustProxy() ? firstHeaderValue(ctx.header("X-Forwarded-Proto")) : null;
		String host = config.trustProxy() ? firstHeaderValue(ctx.header("X-Forwarded-Host")) : null;
		if (proto == null) {
			proto = ctx.scheme();
		}
		if (host == null) {
			host = ctx.host();
		}
		String prefix = config.trustProxy() ? firstHeaderValue(ctx.header("X-Forwarded-Prefix")) : null;
		return proto + "://" + host + (prefix == null ? "" : prefix.replaceAll("/+$", ""));
	}
	
	private static @Nullable String firstHeaderValue(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		int comma = value.indexOf(',');
		return (comma < 0 ? value : value.substring(0, comma)).strip();
	}
	
	/**
	 * Returns the raw (still percent-encoded) path of the request without the context path.
	 */
	public static @NonNull String rawPath(@NonNull Context ctx) {
		String uri = ctx.req().getRequestURI();
		String contextPath = ctx.contextPath();
		if (!contextPath.isEmpty() && !"/".equals(contextPath) && uri.startsWith(contextPath)) {
			uri = uri.substring(contextPath.length());
		}
		return uri;
	}
	
	public static @NonNull String decode(@NonNull String value) {
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}
	
	public static @NonNull List<String> decodedSegments(@NonNull String rawPath) {
		List<String> segments = new ArrayList<>();
		for (String segment : rawPath.split("/")) {
			if (!segment.isEmpty()) {
				segments.add(decode(segment));
			}
		}
		return segments;
	}
	
	public static boolean isHead(@NonNull Context ctx) {
		return "HEAD".equalsIgnoreCase(ctx.req().getMethod());
	}
	
	public static @NonNull String method(@NonNull Context ctx) {
		return ctx.req().getMethod().toUpperCase(Locale.ROOT);
	}
	
	public static boolean accepts(@NonNull Context ctx, @NonNull String mediaType) {
		String accept = ctx.header("Accept");
		return accept != null && accept.toLowerCase(Locale.ROOT).contains(mediaType.toLowerCase(Locale.ROOT));
	}
	
	/**
	 * Redacts secrets that are carried in the request path (e.g. the npm logout token) before the path is logged.<br>
	 */
	public static @NonNull String sanitizePath(@NonNull String path) {
		String marker = "/-/user/token/";
		int index = path.indexOf(marker);
		if (index >= 0) {
			return path.substring(0, index + marker.length()) + "***";
		}
		return path;
	}

	/**
	 * Writes a server generated HTML response with a restrictive content security policy, the pages only contain links.<br>
	 */
	public static void html(@NonNull Context ctx, int status, @NonNull String html) {
		ctx.status(status).header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'").html(html);
	}

	public static @NonNull String escapeHtml(@NonNull String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (char c : value.toCharArray()) {
			switch (c) {
				case '<' -> builder.append("&lt;");
				case '>' -> builder.append("&gt;");
				case '&' -> builder.append("&amp;");
				case '"' -> builder.append("&quot;");
				case '\'' -> builder.append("&#39;");
				default -> builder.append(c);
			}
		}
		return builder.toString();
	}
	
	public static @NonNull String escapeXml(@NonNull String value) {
		return escapeHtml(value);
	}
}
