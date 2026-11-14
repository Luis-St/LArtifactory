package net.luis.artifactory.config;

import net.luis.utils.io.network.mail.SmtpSecurity;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The smtp server used to send two-factor codes, email verification codes and password reset mails.<br>
 * Mail is disabled while no host is configured, the features that need it are then unavailable.<br>
 *
 * @param host The smtp host, null if mail is disabled
 * @param port The smtp port
 * @param username The smtp user, null to send without authentication
 * @param password The smtp password
 * @param from The sender, an address or {@code Name <address>}
 * @param security How the connection is secured: STARTTLS (required, not opportunistic), IMPLICIT_TLS or PLAINTEXT
 */
public record MailConfig(
	@Nullable String host,
	int port,
	@Nullable String username,
	@Nullable String password,
	@Nullable String from,
	@NonNull SmtpSecurity security
) {

	public MailConfig {
		if (port < 1 || port > 65535) {
			throw new ConfigException(EnvKeys.SMTP_PORT + " must be between 1 and 65535, got: " + port);
		}
		if (host != null && from == null) {
			throw new ConfigException(EnvKeys.SMTP_FROM + " is required when " + EnvKeys.SMTP_HOST + " is set");
		}
	}

	@NonNull
	public static MailConfig disabled() {
		return new MailConfig(null, SmtpSecurity.STARTTLS.defaultPort(), null, null, null, SmtpSecurity.STARTTLS);
	}

	@NonNull
	public static MailConfig from(@NonNull Env env) {
		SmtpSecurity security = env.enumeration(SmtpSecurity.class, EnvKeys.SMTP_SECURITY, SmtpSecurity.STARTTLS);
		return new MailConfig(
			env.optional(EnvKeys.SMTP_HOST),
			env.integer(EnvKeys.SMTP_PORT, security.defaultPort()),
			env.optional(EnvKeys.SMTP_USERNAME),
			env.optional(EnvKeys.SMTP_PASSWORD),
			env.optional(EnvKeys.SMTP_FROM),
			security
		);
	}

	public boolean enabled() {
		return this.host != null;
	}

	@Override
	@NonNull
	public String toString() {
		return "MailConfig[host=" + this.host + ", port=" + this.port + ", username=" + this.username + ", from=" + this.from + ", security=" + this.security + "]";
	}
}
