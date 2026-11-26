package net.luis.artifactory.mail;

import net.luis.artifactory.config.MailConfig;
import net.luis.utils.io.network.connection.exception.NetworkConnectionException;
import net.luis.utils.io.network.mail.*;
import net.luis.utils.io.network.mail.message.*;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Sends mails through the configured smtp server with the {@link SmtpClient} of LUtils, one connection per mail.<br>
 * With {@link SmtpSecurity#STARTTLS} the upgrade is required, a server that does not offer it is refused instead of sending in plain text.<br>
 */
public class SmtpMailSender implements MailSender {
	
	private final String host;
	private final int port;
	private final SmtpClientConfig clientConfig;
	private final Mailbox from;
	
	public SmtpMailSender(@NonNull MailConfig config) {
		Objects.requireNonNull(config, "Mail config must not be null");
		if (!config.enabled()) {
			throw new IllegalArgumentException("Mail is not configured");
		}
		this.host = Objects.requireNonNull(config.host());
		this.port = config.port();
		SmtpAuth auth = config.username() == null ? new SmtpAuth.None() : new SmtpAuth.Plain(config.username(), Objects.requireNonNullElse(config.password(), "").toCharArray());
		this.clientConfig = SmtpClientConfig.builder().security(config.security()).auth(auth).build();
		try {
			this.from = Mailbox.parse(Objects.requireNonNull(config.from()));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid sender address: " + config.from(), e);
		}
	}
	
	@Override
	public boolean available() {
		return true;
	}
	
	@Override
	public void send(@NonNull String to, @NonNull Mail mail) throws MailException {
		MailMessage message;
		try {
			message = MailMessage.builder()
				.from(this.from)
				.to(Mailbox.of(MailAddress.parse(to)))
				.subject(mail.subject())
				.content(MultipartContent.alternative(TextContent.of(mail.text()), HtmlContent.of(mail.html())))
				.date(Instant.now())
				.build();
		} catch (IllegalArgumentException e) {
			throw new MailException("Invalid recipient address", e);
		}
		try (SmtpClient client = SmtpClient.connectTo(this.host, this.port, this.clientConfig)) {
			client.send(message);
		} catch (NetworkConnectionException e) {
			throw new MailException("Failed to send mail via " + this.host + ": " + e.getMessage(), e);
		}
	}
}
