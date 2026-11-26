package net.luis.artifactory.mail;

import org.jspecify.annotations.NonNull;

/**
 * Sends mails, used for two-factor codes, email verification and password recovery.<br>
 */
public interface MailSender {
	
	/**
	 * A sender for a server without mail configuration, every feature that needs mail is unavailable.<br>
	 */
	@NonNull
	static MailSender disabled() {
		return new MailSender() {
			@Override
			public boolean available() {
				return false;
			}
			
			@Override
			public void send(@NonNull String to, @NonNull Mail mail) throws MailException {
				throw new MailException("Mail is not configured");
			}
		};
	}
	
	/**
	 * Whether mails can be sent, false if no smtp server is configured.<br>
	 */
	boolean available();
	
	/**
	 * Sends the plain text and the html version of the mail as alternatives, the client shows the best one it supports.<br>
	 */
	void send(@NonNull String to, @NonNull Mail mail) throws MailException;
	
	class MailException extends Exception {
		
		public MailException(@NonNull String message) {
			super(message);
		}
		
		public MailException(@NonNull String message, @NonNull Throwable cause) {
			super(message, cause);
		}
	}
}
