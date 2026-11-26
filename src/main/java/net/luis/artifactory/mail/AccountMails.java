package net.luis.artifactory.mail;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The mails of the account security: sign in and confirmation codes, email verification, address changes and password recovery.<br>
 */
public class AccountMails {

	private final @Nullable String baseUrl;

	/**
	 * @param baseUrl The public url of the server, used for the logo, the footer and the password reset link, null if unknown
	 */
	public AccountMails(@Nullable String baseUrl) {
		this.baseUrl = baseUrl;
	}

	@NonNull
	public Mail signInCode(@NonNull String username, @NonNull String code, int minutes) {
		return Mail.builder("Your LArtifactory sign in code", this.baseUrl)
			.preheader("Your sign in code is " + code + ", valid for " + minutes + " minutes.")
			.greeting(username)
			.paragraph("Your sign in code is:")
			.code(code)
			.paragraph("It is valid for " + minutes + " minutes.")
			.notice("If you did not try to sign in, someone knows your password: change it right away.")
			.build();
	}

	@NonNull
	public Mail confirmationCode(@NonNull String username, @NonNull String code, int minutes) {
		return Mail.builder("Your LArtifactory confirmation code", this.baseUrl)
			.preheader("Your confirmation code is " + code + ", valid for " + minutes + " minutes.")
			.greeting(username)
			.paragraph("Your code to confirm a change of your account security is:")
			.code(code)
			.paragraph("It is valid for " + minutes + " minutes.")
			.notice("If you did not change your account security, someone is signed in to your account: change your password right away.")
			.build();
	}

	@NonNull
	public Mail emailVerification(@NonNull String username, @NonNull String code, int minutes) {
		return Mail.builder("Verify your email address for LArtifactory", this.baseUrl)
			.preheader("Your verification code is " + code + ", valid for " + minutes + " minutes.")
			.greeting(username)
			.paragraph("Your verification code is:")
			.code(code)
			.paragraph("It is valid for " + minutes + " minutes. If you did not add this address to an account, ignore this mail.")
			.build();
	}

	/**
	 * Sent to the previous address after the email address of an account was changed.<br>
	 *
	 * @param maskedEmail The new address, masked
	 */
	@NonNull
	public Mail emailChanged(@NonNull String username, @NonNull String maskedEmail) {
		return Mail.builder("Your LArtifactory email address was changed", this.baseUrl)
			.preheader("The email address of your account was changed to " + maskedEmail + ".")
			.greeting(username)
			.paragraph("The email address of your account was changed to " + maskedEmail + ".")
			.notice("If you did not do this, contact an administrator right away.")
			.build();
	}

	/**
	 * A reset link if the public url of the server is known, otherwise the reset code to enter on the sign in page.<br>
	 */
	@NonNull
	public Mail passwordReset(@NonNull String username, @NonNull String token, int minutes) {
		Mail.Builder builder = Mail.builder("Reset your LArtifactory password", this.baseUrl)
			.preheader("Someone asked to reset the password of your account.")
			.greeting(username);
		if (this.baseUrl == null) {
			builder.paragraph("Someone asked to reset the password of your account. Open the sign in page, choose \"Forgot password\" and enter this reset code:")
				.code(token);
		} else {
			builder.paragraph("Someone asked to reset the password of your account. Open this link to choose a new password:")
				.button("Choose a new password", this.baseUrl + "/ui/#/reset-password?token=" + token);
		}
		return builder.paragraph("It is valid for " + minutes + " minutes and can be used once.")
			.notice("If you did not ask for this, ignore this mail, your password stays unchanged.")
			.build();
	}
}
