package net.luis.artifactory.http;

import io.javalin.http.Context;
import net.luis.artifactory.config.ServerConfig;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class Requests {
	
	/**
	 * The IMF-fixdate format of HTTP, the day of month always has two digits unlike {@link DateTimeFormatter#RFC_1123_DATE_TIME}.<br>
	 */
	private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
	
	private Requests() {}
	
	@NonNull
	public static String httpDate(@NonNull Instant instant) {
		return HTTP_DATE.format(instant);
	}
	
	@NonNull
	public static String baseUrl(@NonNull Context ctx, @NonNull ServerConfig config) {
		if (config.baseUrl() != null) {
			return config.baseUrl();
		}
		
		// X-Forwarded-* headers are client controlled, only honored when the deployment opts in (behind a trusted proxy)
		boolean trustProxy = config.trustProxy();
		String proto = trustProxy ? firstHeaderValue(ctx.header("X-Forwarded-Proto")) : null;
		String host = trustProxy ? firstHeaderValue(ctx.header("X-Forwarded-Host")) : null;
		if (proto == null) {
			proto = ctx.scheme();
		}
		if (host == null) {
			host = ctx.host();
		}
		String prefix = trustProxy ? firstHeaderValue(ctx.header("X-Forwarded-Prefix")) : null;
		return proto + "://" + host + (prefix == null ? "" : prefix.replaceAll("/+$", ""));
	}
	
	/**
	 * Returns the address of the client, the last {@code X-Forwarded-For} entry is only honored behind a trusted proxy.<br>
	 * The proxy appends the address it received the request from, all entries before it are sent by the client and can be forged.<br>
	 */
	@NonNull
	public static String clientIp(@NonNull Context ctx, @NonNull ServerConfig config) {
		// A client sent header may arrive as its own header line before the one of the proxy
		List<String> values = config.trustProxy() ? Collections.list(ctx.req().getHeaders("X-Forwarded-For")) : List.of();
		String forwarded = values.isEmpty() ? null : lastHeaderValue(values.getLast());
		return forwarded == null ? ctx.req().getRemoteAddr() : forwarded;
	}
	
	@Nullable
	private static String firstHeaderValue(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		int comma = value.indexOf(',');
		return (comma < 0 ? value : value.substring(0, comma)).strip();
	}
	
	@Nullable
	private static String lastHeaderValue(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String last = value.substring(value.lastIndexOf(',') + 1).strip();
		return last.isEmpty() ? null : last;
	}
	
	/**
	 * Returns the raw (still percent-encoded) path of the request without the context path.
	 */
	@NonNull
	public static String rawPath(@NonNull Context ctx) {
		String uri = ctx.req().getRequestURI();
		String contextPath = ctx.contextPath();
		if (!contextPath.isEmpty() && !"/".equals(contextPath) && uri.startsWith(contextPath)) {
			uri = uri.substring(contextPath.length());
		}
		return uri;
	}
	
	@NonNull
	public static String decode(@NonNull String value) {
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}
	
	@NonNull
	public static List<String> decodedSegments(@NonNull String rawPath) {
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
	
	@NonNull
	public static String method(@NonNull Context ctx) {
		return ctx.req().getMethod().toUpperCase(Locale.ROOT);
	}
	
	public static boolean accepts(@NonNull Context ctx, @NonNull String mediaType) {
		String accept = ctx.header("Accept");
		return accept != null && accept.toLowerCase(Locale.ROOT).contains(mediaType.toLowerCase(Locale.ROOT));
	}
	
	/**
	 * Selects the offered media type the client prefers according to the quality values of its {@code Accept} header.<br>
	 * The quality of an offered type is taken from the most specific matching range ({@code type/subtype} before {@code type/*} before {@code *}{@code /*}),
	 * on equal quality the type offered first wins.<br>
	 *
	 * @param offered The media types the server can produce, in the order of the server preference
	 * @return The selected type, the first offered type without {@code Accept} header and null if the client accepts none of them
	 */
	@Nullable
	public static String negotiate(@NonNull Context ctx, @NonNull List<String> offered) {
		String header = ctx.header("Accept");
		if (header == null || header.isBlank()) {
			return offered.getFirst();
		}
		List<String[]> ranges = new ArrayList<>();
		for (String part : header.split(",")) {
			String[] params = part.split(";");
			String range = params[0].strip().toLowerCase(Locale.ROOT);
			if (range.isEmpty()) {
				continue;
			}
			String quality = "1";
			for (int i = 1; i < params.length; i++) {
				String param = params[i].strip();
				if (param.regionMatches(true, 0, "q=", 0, 2)) {
					quality = param.substring(2).strip();
				}
			}
			ranges.add(new String[] { range, quality });
		}
		
		String best = null;
		double bestQuality = 0;
		for (String type : offered) {
			String lower = type.toLowerCase(Locale.ROOT);
			String wildcard = lower.substring(0, lower.indexOf('/') + 1) + "*";
			int specificity = -1;
			double quality = 0;
			for (String[] range : ranges) {
				int match = range[0].equals(lower) ? 2 : range[0].equals(wildcard) ? 1 : "*/*".equals(range[0]) ? 0 : -1;
				if (match > specificity) {
					specificity = match;
					quality = parseQuality(range[1]);
				}
			}
			if (quality > bestQuality) {
				best = type;
				bestQuality = quality;
			}
		}
		return best;
	}
	
	private static double parseQuality(@NonNull String value) {
		try {
			return Math.clamp(Double.parseDouble(value), 0, 1);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
	
	/**
	 * Writes a server generated HTML response with a restrictive content security policy, the pages only contain links.<br>
	 */
	public static void html(@NonNull Context ctx, int status, @NonNull String html) {
		ctx.status(status).header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'").html(html);
	}
	
	@NonNull
	public static String escapeHtml(@NonNull String value) {
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
	
	@NonNull
	public static String escapeXml(@NonNull String value) {
		return escapeHtml(value);
	}
	
	/**
	 * Redacts token secrets ({@code lat_...}) that appear in a request path (e.g. the npm logout url) so they are not written to logs.
	 */
	@NonNull
	public static String sanitizePath(@NonNull String path) {
		if (!path.contains("lat_")) {
			return path;
		}
		String[] segments = path.split("/", -1);
		StringBuilder builder = new StringBuilder(path.length());
		for (int i = 0; i < segments.length; i++) {
			if (i > 0) {
				builder.append('/');
			}
			builder.append(segments[i].startsWith("lat_") ? "lat_***" : segments[i]);
		}
		return builder.toString();
	}
}
