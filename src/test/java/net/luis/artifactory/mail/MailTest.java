package net.luis.artifactory.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renders the plain text and the html version of the account mails.<br>
 */
class MailTest {
	
	@Test
	void containsCodeInBothVersions() {
		Mail mail = new AccountMails(null).signInCode("jane", "123456", 10);
		assertEquals("Your LArtifactory sign in code", mail.subject());
		assertTrue(mail.text().contains("Hello jane,"), mail.text());
		assertTrue(mail.text().contains("123456"), mail.text());
		assertTrue(mail.html().contains(">123456</td>"), mail.html());
		assertFalse(mail.html().contains("<img"), "no logo image without a public url");
	}
	
	@Test
	void loadsLogoFromBaseUrl() {
		Mail mail = new AccountMails("https://artifacts.example.com").emailChanged("jane", "j***@example.com");
		assertTrue(mail.html().contains("src=\"https://artifacts.example.com/ui/mail-logo.png\""), mail.html());
		assertTrue(mail.html().contains("artifacts.example.com</a>"), mail.html());
	}
	
	@Test
	void escapesHtml() {
		Mail mail = new AccountMails(null).emailVerification("<script>&\"'", "123456", 10);
		assertTrue(mail.html().contains("&lt;script&gt;&amp;&quot;&#39;"), mail.html());
		assertFalse(mail.html().contains("<script>"), mail.html());
		assertTrue(mail.text().contains("Hello <script>&\"',"), "the plain text is not escaped");
	}
	
	@Test
	void passwordResetWithLinkOrCode() {
		Mail link = new AccountMails("https://artifacts.example.com").passwordReset("jane", "abc", 30);
		assertTrue(link.html().contains("href=\"https://artifacts.example.com/ui/#/reset-password?token=abc\""), link.html());
		assertTrue(link.text().contains("https://artifacts.example.com/ui/#/reset-password?token=abc"), link.text());
		Mail code = new AccountMails(null).passwordReset("jane", "abc", 30);
		assertTrue(code.text().matches("(?s).*reset code:\\s+abc.*"), code.text());
		assertFalse(code.html().contains("href="), code.html());
	}
	
	@Test
	void rejectsNonHttpButton() {
		assertThrows(IllegalArgumentException.class, () -> Mail.builder("Subject", null).button("Open", "javascript:alert(1)"));
	}
}
