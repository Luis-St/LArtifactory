package net.luis.artifactory.mail;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.*;

/**
 * A mail with a plain text and an html version of the same content, sent as {@code multipart/alternative}.<br>
 * Built from blocks with {@link #builder}, so both versions always say the same.<br>
 *
 * @param subject The subject
 * @param text The plain text version
 * @param html The html version
 */
public record Mail(
	@NonNull String subject,
	@NonNull String text,
	@NonNull String html
) {

	private static final String ACCENT = "#4f46e5";
	private static final String TEXT = "#161a21";
	private static final String MUTED = "#5f6775";
	private static final String FAINT = "#8a919d";
	private static final String BORDER = "#e1e4ea";
	private static final String BACKGROUND = "#f5f6f8";
	private static final String FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
	private static final String MONO = "'SFMono-Regular', Menlo, Consolas, 'Liberation Mono', monospace";

	public Mail {
		Objects.requireNonNull(subject, "Subject must not be null");
		Objects.requireNonNull(text, "Text must not be null");
		Objects.requireNonNull(html, "Html must not be null");
	}

	/**
	 * Starts a mail in the layout of the web ui.<br>
	 *
	 * @param subject The subject, also the heading of the mail
	 * @param baseUrl The public url of the server, the logo is loaded from it and the footer links to it, null to use a logo without image
	 */
	@NonNull
	public static Builder builder(@NonNull String subject, @Nullable String baseUrl) {
		return new Builder(subject, baseUrl);
	}

	@NonNull
	static String escape(@NonNull String value) {
		StringBuilder builder = new StringBuilder(value.length());
		for (char c : value.toCharArray()) {
			switch (c) {
				case '&' -> builder.append("&amp;");
				case '<' -> builder.append("&lt;");
				case '>' -> builder.append("&gt;");
				case '"' -> builder.append("&quot;");
				case '\'' -> builder.append("&#39;");
				default -> builder.append(c);
			}
		}
		return builder.toString();
	}

	private sealed interface Block {}

	private record Greeting(@NonNull String name) implements Block {}

	private record Paragraph(@NonNull String text) implements Block {}

	private record Code(@NonNull String code) implements Block {}

	private record Button(@NonNull String label, @NonNull String url) implements Block {}

	private record Notice(@NonNull String text) implements Block {}

	public static final class Builder {

		private final String subject;
		private final @Nullable String baseUrl;
		private final List<Block> blocks = new ArrayList<>();
		private String preheader = "";

		private Builder(@NonNull String subject, @Nullable String baseUrl) {
			this.subject = Objects.requireNonNull(subject, "Subject must not be null");
			this.baseUrl = baseUrl;
		}

		/**
		 * The preview text that mail clients show next to the subject, not visible in the mail itself.<br>
		 */
		@NonNull
		public Builder preheader(@NonNull String preheader) {
			this.preheader = Objects.requireNonNull(preheader, "Preheader must not be null");
			return this;
		}

		@NonNull
		public Builder greeting(@NonNull String name) {
			this.blocks.add(new Greeting(Objects.requireNonNull(name, "Name must not be null")));
			return this;
		}

		@NonNull
		public Builder paragraph(@NonNull String text) {
			this.blocks.add(new Paragraph(Objects.requireNonNull(text, "Text must not be null")));
			return this;
		}

		/**
		 * A code to enter, shown large and on its own line.<br>
		 */
		@NonNull
		public Builder code(@NonNull String code) {
			this.blocks.add(new Code(Objects.requireNonNull(code, "Code must not be null")));
			return this;
		}

		/**
		 * A link shown as button, followed by the plain link for clients that do not render it.<br>
		 */
		@NonNull
		public Builder button(@NonNull String label, @NonNull String url) {
			Objects.requireNonNull(label, "Label must not be null");
			Objects.requireNonNull(url, "Url must not be null");
			String scheme = URI.create(url).getScheme();
			if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
				throw new IllegalArgumentException("Url must be http or https: " + url);
			}
			this.blocks.add(new Button(label, url));
			return this;
		}

		/**
		 * A highlighted security note, e.g. what to do if the mail was not expected.<br>
		 */
		@NonNull
		public Builder notice(@NonNull String text) {
			this.blocks.add(new Notice(Objects.requireNonNull(text, "Text must not be null")));
			return this;
		}

		@NonNull
		public Mail build() {
			return new Mail(this.subject, this.text(), this.html());
		}

		@NonNull
		private String host() {
			String host = this.baseUrl == null ? null : URI.create(this.baseUrl).getHost();
			return host == null ? Objects.requireNonNullElse(this.baseUrl, "") : host;
		}

		@NonNull
		private String text() {
			StringBuilder builder = new StringBuilder();
			for (Block block : this.blocks) {
				switch (block) {
					case Greeting greeting -> builder.append("Hello ").append(greeting.name()).append(",");
					case Paragraph paragraph -> builder.append(paragraph.text());
					case Code code -> builder.append("    ").append(code.code());
					case Button button -> builder.append(button.label()).append(":\n").append(button.url());
					case Notice notice -> builder.append(notice.text());
				}
				builder.append("\n\n");
			}
			builder.append("-- \nLArtifactory");
			if (this.baseUrl != null) {
				builder.append(" - ").append(this.baseUrl);
			}
			builder.append("\nThis mail was sent automatically, replies are not read.\n");
			return builder.toString();
		}

		@NonNull
		private String html() {
			StringBuilder content = new StringBuilder();
			for (Block block : this.blocks) {
				switch (block) {
					case Greeting greeting -> content.append("<p style=\"margin:0 0 16px;font-size:15px;line-height:24px;color:").append(TEXT).append(";\">Hello <strong>")
						.append(escape(greeting.name())).append("</strong>,</p>");
					case Paragraph paragraph -> content.append("<p style=\"margin:0 0 16px;font-size:15px;line-height:24px;color:").append(TEXT).append(";\">")
						.append(escape(paragraph.text())).append("</p>");
					case Code code -> content.append(codeHtml(code.code()));
					case Button button -> content.append(buttonHtml(button.label(), button.url()));
					case Notice notice -> content.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 16px;\"><tr>")
						.append("<td style=\"background:#fcf2df;border-left:4px solid #e3a33b;border-radius:6px;padding:12px 16px;font-size:13px;line-height:20px;color:#7a4705;\">")
						.append(escape(notice.text())).append("</td></tr></table>");
				}
			}
			String logo = this.baseUrl == null
				? "<td width=\"40\" height=\"40\" align=\"center\" valign=\"middle\" bgcolor=\"" + ACCENT + "\" style=\"width:40px;height:40px;border-radius:9px;font-size:22px;font-weight:700;color:#ffffff;font-family:" + FONT + ";\">L</td>"
				: "<td width=\"40\" height=\"40\" bgcolor=\"" + ACCENT + "\" style=\"width:40px;height:40px;border-radius:9px;\"><img src=\"" + escape(this.baseUrl + "/ui/mail-logo.png") + "\" width=\"40\" height=\"40\" alt=\"L\" style=\"display:block;border:0;outline:none;width:40px;height:40px;border-radius:9px;\"></td>";
			String site = this.baseUrl == null ? ""
				: " at <a href=\"" + escape(this.baseUrl) + "\" style=\"color:" + MUTED + ";text-decoration:underline;\">" + escape(this.host()) + "</a>";
			return """
				<!doctype html>
				<html lang="en">
				<head>
				<meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<meta name="color-scheme" content="light">
				<meta name="supported-color-schemes" content="light">
				<title>%1$s</title>
				</head>
				<body style="margin:0;padding:0;background:%2$s;font-family:%3$s;-webkit-text-size-adjust:100%%;">
				<div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">%4$s</div>
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" bgcolor="%2$s" style="background:%2$s;">
				<tr><td align="center" style="padding:32px 16px;">
				<table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px;">
				<tr><td style="padding:0 4px 20px;">
				<table role="presentation" cellpadding="0" cellspacing="0"><tr>
				%5$s
				<td style="padding-left:12px;font-family:%3$s;font-size:19px;font-weight:700;letter-spacing:-0.2px;color:%6$s;">LArtifactory</td>
				</tr></table>
				</td></tr>
				<tr><td bgcolor="#ffffff" style="background:#ffffff;border:1px solid %7$s;border-top:4px solid %8$s;border-radius:12px;padding:32px 32px 16px;font-family:%3$s;">
				<h1 style="margin:0 0 20px;font-size:22px;line-height:30px;font-weight:700;color:%6$s;">%1$s</h1>
				%9$s
				</td></tr>
				<tr><td style="padding:20px 4px 0;font-family:%3$s;font-size:12px;line-height:18px;color:%10$s;">
				Sent by LArtifactory%11$s. This mail was sent automatically, replies are not read.
				</td></tr>
				</table>
				</td></tr>
				</table>
				</body>
				</html>
				""".formatted(escape(this.subject), BACKGROUND, FONT, escape(this.preheader), logo, TEXT, BORDER, ACCENT, content, FAINT, site);
		}

		@NonNull
		private static String codeHtml(@NonNull String code) {
			// Short codes are typed, they are shown large and spaced, long reset codes are copied and must wrap
			boolean small = code.length() <= 12;
			String size = small ? "font-size:32px;line-height:40px;letter-spacing:8px;text-indent:8px;font-weight:700;" : "font-size:15px;line-height:22px;word-break:break-all;";
			return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 20px;\"><tr>"
				+ "<td align=\"center\" bgcolor=\"#eef0ff\" style=\"background:#eef0ff;border:1px solid #d9dcfb;border-radius:10px;padding:18px 16px;font-family:" + MONO + ";" + size + "color:" + ACCENT + ";\">"
				+ escape(code) + "</td></tr></table>";
		}

		@NonNull
		private static String buttonHtml(@NonNull String label, @NonNull String url) {
			String href = escape(url);
			return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 16px;\"><tr>"
				+ "<td bgcolor=\"" + ACCENT + "\" style=\"border-radius:8px;\"><a href=\"" + href + "\" target=\"_blank\" style=\"display:inline-block;padding:12px 24px;font-family:" + FONT + ";font-size:15px;font-weight:600;color:#ffffff;text-decoration:none;border-radius:8px;\">"
				+ escape(label) + "</a></td></tr></table>"
				+ "<p style=\"margin:0 0 16px;font-size:12px;line-height:18px;color:" + MUTED + ";\">If the button does not work, open this link:<br>"
				+ "<a href=\"" + href + "\" target=\"_blank\" style=\"color:" + ACCENT + ";word-break:break-all;\">" + href + "</a></p>";
		}
	}
}
