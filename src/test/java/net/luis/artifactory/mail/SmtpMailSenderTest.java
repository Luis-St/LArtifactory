package net.luis.artifactory.mail;

import net.luis.artifactory.config.MailConfig;
import net.luis.utils.io.network.mail.SmtpSecurity;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sends a mail through the LUtils smtp client to a Mailpit server, skipped if docker is not available.<br>
 */
@Testcontainers(disabledWithoutDocker = true)
class SmtpMailSenderTest {
	
	@Container
	private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.21").withExposedPorts(1025, 8025);
	
	@Test
	void sendsMail() throws Exception {
		MailConfig config = new MailConfig(MAILPIT.getHost(), MAILPIT.getMappedPort(1025), null, null, "LArtifactory <noreply@example.com>", SmtpSecurity.PLAINTEXT);
		new SmtpMailSender(config).send("jane@example.com", new AccountMails("https://artifacts.example.com").signInCode("jane", "123456", 10));
		
		HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1/messages")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("jane@example.com"), response.body());
		assertTrue(response.body().contains("Your LArtifactory sign in code"), response.body());
	}
	
	@Test
	void refusesServerWithoutStartTls() {
		MailConfig config = new MailConfig(MAILPIT.getHost(), MAILPIT.getMappedPort(1025), null, null, "noreply@example.com", SmtpSecurity.STARTTLS);
		assertThrows(MailSender.MailException.class, () -> new SmtpMailSender(config).send("jane@example.com", new Mail("Subject", "text", "<p>text</p>")));
	}
}
