package net.luis.artifactory.auth;

import net.luis.artifactory.config.ServerConfig;
import net.luis.artifactory.database.entity.UserEntity;
import net.luis.artifactory.database.entity.UserSecurityEntity;
import net.luis.artifactory.http.HttpError;
import net.luis.artifactory.mail.*;
import net.luis.artifactory.repository.AuditService;
import net.luis.utils.io.database.exception.SqlException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Email addresses, two-factor authentication and password recovery of user accounts.<br>
 * <p>
 *     Second factors are a code of an authenticator app (TOTP), a code sent to the verified email address, or one of the
 *     {@link RecoveryCodes recovery codes}. A user with a second factor signs in to the web ui in two steps: the password
 *     creates a {@link Challenge challenge}, which a code of one of the enabled factors completes.
 * </p>
 * <p>
 *     Challenges, setups in progress, email codes and password reset tokens only live in memory, they are short lived and a restart
 *     simply requires starting over. Every failed code counts towards the login throttle of the client and the user.
 * </p>
 */
public class AccountSecurityService {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(AccountSecurityService.class);
	private static final String TOTP_PURPOSE = "totp-secret";
	private static final String CODE_PURPOSE = "mail-code";
	private static final String ISSUER = "LArtifactory";
	private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(10);
	private static final Duration SETUP_LIFETIME = Duration.ofMinutes(15);
	private static final Duration MAIL_COOLDOWN = Duration.ofSeconds(30);
	private static final int MAX_ATTEMPTS = 5;
	private static final int MAX_MAILS = 5;
	private static final int MAX_ENTRIES = 10_000;
	private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s.]+");
	private static final SecureRandom RANDOM = new SecureRandom();
	
	private final UserService users;
	private final AuthService auth;
	private final Pepper pepper;
	private final MailSender mail;
	private final AccountMails mails;
	private final ServerConfig config;
	private final AuditService audit;
	private final TokenUsageService tokenUsage;
	private final LoginThrottle throttle;
	private final Executor mailExecutor;
	private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
	private final Map<String, PendingTotp> pendingTotp = new ConcurrentHashMap<>();
	private final Map<String, PendingEmail> pendingEmails = new ConcurrentHashMap<>();
	private final Map<String, StepUpCode> stepUpCodes = new ConcurrentHashMap<>();
	private final Map<String, PasswordReset> resets = new ConcurrentHashMap<>();
	private final Map<String, Instant> resetMails = new ConcurrentHashMap<>();
	
	public AccountSecurityService(@NonNull UserService users, @NonNull AuthService auth, @NonNull Pepper pepper, @NonNull MailSender mail, @NonNull ServerConfig config, @NonNull AuditService audit, @NonNull TokenUsageService tokenUsage) {
		this.users = Objects.requireNonNull(users, "User service must not be null");
		this.auth = Objects.requireNonNull(auth, "Auth service must not be null");
		this.pepper = Objects.requireNonNull(pepper, "Pepper must not be null");
		this.mail = Objects.requireNonNull(mail, "Mail sender must not be null");
		this.config = Objects.requireNonNull(config, "Config must not be null");
		this.mails = new AccountMails(config.baseUrl());
		this.audit = Objects.requireNonNull(audit, "Audit service must not be null");
		this.tokenUsage = Objects.requireNonNull(tokenUsage, "Token usage service must not be null");
		this.throttle = auth.throttle();
		// Password reset mails are sent in the background, so the response time does not tell whether the account exists
		this.mailExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("mail").daemon().factory());
	}
	
	//region Helpers
	@NonNull
	private static String sixDigits() {
		return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
	}
	
	/**
	 * Masks an email address for display before sign in, e.g. {@code j***@example.com}.<br>
	 */
	@NonNull
	public static String maskEmail(@NonNull String email) {
		int at = email.indexOf('@');
		if (at <= 0) {
			return "***";
		}
		return email.charAt(0) + "***" + email.substring(at);
	}
	
	@NonNull
	public static String normalizeEmail(@Nullable String email) {
		String value = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
		if (value.length() > 320 || !EMAIL.matcher(value).matches()) {
			throw HttpError.badRequest("Invalid email address");
		}
		return value;
	}
	
	@NonNull
	private String hashCode(@NonNull String code) {
		return this.pepper.hmacHex(CODE_PURPOSE, code);
	}
	
	private boolean codeMatches(@Nullable String expectedHash, @Nullable String code) {
		if (expectedHash == null || code == null) {
			return false;
		}
		String normalized = code.replaceAll("\\s", "");
		return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII), this.hashCode(normalized).getBytes(StandardCharsets.US_ASCII));
	}
	
	@NonNull
	private Duration codeLifetime() {
		return Duration.ofMinutes(this.config.mailCodeMinutes());
	}
	
	private void requireMail() {
		if (!this.mail.available()) {
			throw HttpError.of(503, "Mail is not configured on this server");
		}
	}
	
	private void send(@NonNull String to, @NonNull Mail mail) {
		try {
			this.mail.send(to, mail);
		} catch (MailSender.MailException e) {
			LOGGER.warn("Failed to send mail '{}'", mail.subject(), e);
			throw HttpError.of(502, "The mail could not be sent, try again later");
		}
	}
	
	private void limitEntries(@NonNull Map<String, ?> map) {
		if (map.size() >= MAX_ENTRIES) {
			this.cleanup();
			if (map.size() >= MAX_ENTRIES) {
				throw HttpError.of(503, "Too many pending requests, try again later");
			}
		}
	}
	
	@NonNull
	private String account(@NonNull String username) {
		if (this.config.baseUrl() == null) {
			return username;
		}
		String host = URI.create(this.config.baseUrl()).getHost();
		return host == null ? username : username + "@" + host;
	}
	
	/**
	 * Removes expired challenges, setups, codes and reset tokens.<br>
	 */
	public void cleanup() {
		Instant now = Instant.now();
		this.challenges.values().removeIf(challenge -> challenge.expired(now));
		this.pendingTotp.values().removeIf(pending -> pending.expiresAt().isBefore(now));
		this.pendingEmails.values().removeIf(pending -> pending.expiresAt().isBefore(now));
		this.stepUpCodes.values().removeIf(pending -> pending.expiresAt().isBefore(now));
		this.resets.values().removeIf(reset -> reset.expired(now));
		this.resetMails.values().removeIf(sent -> sent.plus(MAIL_COOLDOWN).isBefore(now));
	}
	
	public boolean mailAvailable() {
		return this.mail.available();
	}
	//endregion
	
	//region Second factor verification
	
	/**
	 * Checks a code of the authenticator app, a code is accepted only once.<br>
	 */
	private boolean verifyTotp(@NonNull UserSecurityEntity security, @Nullable String code) throws SqlException {
		if (!security.totpEnabled() || security.totpSecret() == null) {
			return false;
		}
		String secret = this.pepper.decrypt(TOTP_PURPOSE, security.totpSecret());
		if (secret == null) {
			LOGGER.error("The authenticator secret of user '{}' can not be decrypted, the password pepper changed", security.username());
			return false;
		}
		long step = Totp.verify(secret, code, Instant.now(), security.totpLastStep());
		return step >= 0 && this.users.advanceTotpStep(security.username(), security.totpLastStep(), step);
	}
	
	/**
	 * Consumes a recovery code.<br>
	 */
	private boolean verifyRecoveryCode(@NonNull UserSecurityEntity security, @Nullable String code) throws SqlException {
		String remaining = RecoveryCodes.consume(this.pepper, security.recoveryCodes(), code);
		if (remaining == null || security.recoveryCodes() == null || !this.users.replaceRecoveryCodes(security.username(), security.recoveryCodes(), remaining)) {
			return false;
		}
		this.audit.record(security.username(), AuditService.MFA_RECOVERY_USED, null, security.username(), null, RecoveryCodes.remaining(remaining) + " recovery codes left");
		return true;
	}
	
	/**
	 * Counts a wrong code towards the login throttle.<br>
	 */
	private void failure(@NonNull String address, @NonNull String username, @NonNull String method) {
		boolean locked = this.throttle.failure(address, username);
		this.audit.record(username, AuditService.MFA_FAILED, null, username, address, method);
		if (locked) {
			this.audit.record(username, AuditService.LOGIN_LOCKED, null, username, address, "locked for " + this.config.loginLockoutMinutes() + " minutes");
		}
	}
	//endregion
	
	//region Sign in challenges
	
	/**
	 * Starts the second step of a sign in, after the password of a user with a second factor was verified.<br>
	 */
	@NonNull
	public Challenge startChallenge(@NonNull UserEntity user, boolean remember) throws SqlException {
		this.limitEntries(this.challenges);
		UserSecurityEntity security = this.users.security(user.username());
		List<String> methods = new ArrayList<>();
		if (security.totpEnabled()) {
			methods.add(MfaMethod.TOTP.id());
		}
		if (security.emailMfaEnabled() && security.email() != null && this.mail.available()) {
			methods.add(MfaMethod.EMAIL.id());
		}
		if (RecoveryCodes.remaining(security.recoveryCodes()) > 0) {
			methods.add(MfaMethod.RECOVERY.id());
		}
		if (methods.isEmpty()) {
			// Codes by email are the only factor, but no mail can be sent and no recovery code is left: a challenge could never be completed
			LOGGER.warn("User '{}' can not sign in, codes by email are the only second factor but the server can not send mails and no recovery code is left", user.username());
			throw HttpError.of(503, "None of the second factors of this account can be used right now, codes by email need a mail server. Ask an administrator to reset your two-factor authentication");
		}
		Challenge challenge = new Challenge(PasswordHasher.randomSecret(32), user.username(), remember, Instant.now().plus(CHALLENGE_LIFETIME), List.copyOf(methods),
			security.emailMfaEnabled() && security.email() != null ? maskEmail(security.email()) : null);
		this.challenges.put(challenge.id(), challenge);
		return challenge;
	}
	
	@NonNull
	private Challenge challenge(@Nullable String id) {
		Challenge challenge = id == null ? null : this.challenges.get(id);
		if (challenge == null || challenge.expired(Instant.now())) {
			throw HttpError.of(401, "The sign in expired, sign in with your password again");
		}
		return challenge;
	}
	
	/**
	 * Sends a code for the challenge to the email address of the user.<br>
	 */
	public void sendChallengeCode(@Nullable String challengeId) throws SqlException {
		this.requireMail();
		Challenge challenge = this.challenge(challengeId);
		UserSecurityEntity security = this.users.security(challenge.username());
		if (!security.emailMfaEnabled() || security.email() == null) {
			throw HttpError.badRequest("Email codes are not enabled for this account");
		}
		String code = sixDigits();
		synchronized (challenge) {
			Instant now = Instant.now();
			if (challenge.mailsSent >= MAX_MAILS) {
				throw HttpError.of(429, "Too many codes requested, sign in again");
			}
			if (challenge.lastMailAt != null && challenge.lastMailAt.plus(MAIL_COOLDOWN).isAfter(now)) {
				throw HttpError.of(429, "A code was just sent, wait a moment before requesting another one");
			}
			challenge.mailsSent++;
			challenge.lastMailAt = now;
			challenge.emailCodeHash = this.hashCode(code);
			challenge.emailCodeExpiresAt = now.plus(this.codeLifetime());
		}
		this.send(security.email(), this.mails.signInCode(challenge.username(), code, this.config.mailCodeMinutes()));
	}
	
	/**
	 * Completes a challenge with a code of one of its methods.<br>
	 *
	 * @return The completed challenge, a session can be created for its user
	 * @throws HttpError 401 if the code is wrong, the challenge ends after {@value #MAX_ATTEMPTS} wrong codes
	 */
	@NonNull
	public Challenge completeChallenge(@NonNull String address, @Nullable String challengeId, @Nullable String method, @Nullable String code) throws SqlException {
		Challenge challenge = this.challenge(challengeId);
		this.throttle.check(address, challenge.username());
		MfaMethod parsed = MfaMethod.parse(method);
		if (parsed == null || !challenge.methods().contains(parsed.id())) {
			throw HttpError.badRequest("Method must be one of " + String.join(", ", challenge.methods()));
		}
		UserSecurityEntity security = this.users.security(challenge.username());
		boolean valid = switch (parsed) {
			case TOTP -> this.verifyTotp(security, code);
			case RECOVERY -> this.verifyRecoveryCode(security, code);
			case EMAIL -> {
				synchronized (challenge) {
					boolean matches = security.emailMfaEnabled() && challenge.emailCodeExpiresAt != null && challenge.emailCodeExpiresAt.isAfter(Instant.now()) && this.codeMatches(challenge.emailCodeHash, code);
					if (matches) {
						challenge.emailCodeHash = null;
					}
					yield matches;
				}
			}
		};
		if (!valid) {
			this.failure(address, challenge.username(), parsed.id());
			synchronized (challenge) {
				if (++challenge.attempts >= MAX_ATTEMPTS) {
					this.challenges.remove(challenge.id());
					throw HttpError.of(401, "Too many wrong codes, sign in with your password again");
				}
			}
			throw HttpError.of(401, "The code is not valid");
		}
		// A challenge creates exactly one session
		if (this.challenges.remove(challenge.id()) == null) {
			throw HttpError.of(401, "The sign in expired, sign in with your password again");
		}
		this.throttle.success(address, challenge.username());
		return challenge;
	}
	//endregion
	
	//region Confirming changes with a second factor
	
	/**
	 * Sends a code to the email address of a signed in user with codes by email, it confirms one change of the account security.<br>
	 */
	public void sendStepUpCode(@NonNull String username) throws SqlException {
		this.requireMail();
		UserSecurityEntity security = this.users.security(username);
		if (!security.emailMfaEnabled() || security.email() == null) {
			throw HttpError.badRequest("Codes by email are not enabled for this account");
		}
		StepUpCode previous = this.stepUpCodes.get(username);
		Instant now = Instant.now();
		if (previous != null && previous.sentAt().plus(MAIL_COOLDOWN).isAfter(now)) {
			throw HttpError.of(429, "A code was just sent, wait a moment before requesting another one");
		}
		this.limitEntries(this.stepUpCodes);
		String code = sixDigits();
		this.stepUpCodes.put(username, new StepUpCode(this.hashCode(code), now, now.plus(this.codeLifetime()), new int[1]));
		this.send(security.email(), this.mails.confirmationCode(username, code, this.config.mailCodeMinutes()));
	}
	
	/**
	 * Checks a code sent by {@link #sendStepUpCode}, a code is accepted once.<br>
	 */
	private boolean verifyStepUpCode(@NonNull UserSecurityEntity security, @Nullable String code) {
		StepUpCode pending = this.stepUpCodes.get(security.username());
		if (!security.emailMfaEnabled() || pending == null || pending.expiresAt().isBefore(Instant.now())) {
			return false;
		}
		synchronized (pending) {
			if (this.codeMatches(pending.codeHash(), code)) {
				return this.stepUpCodes.remove(security.username(), pending);
			}
			if (++pending.attempts()[0] >= MAX_ATTEMPTS) {
				this.stepUpCodes.remove(security.username(), pending);
			}
			return false;
		}
	}
	
	/**
	 * Requires a second factor for a change of the account security if the user has two-factor authentication.<br>
	 * With the password alone, a stolen session must not be able to turn off or redirect the second factors.<br>
	 *
	 * @param code A code of the authenticator app, a code of {@link #sendStepUpCode} or a recovery code
	 * @throws HttpError 403 if the code is missing or wrong
	 */
	public void requireSecondFactor(@NonNull String username, @NonNull String address, @Nullable String code) throws SqlException {
		UserSecurityEntity security = this.users.security(username);
		if (!security.mfaEnabled()) {
			return;
		}
		this.throttle.check(address, username);
		if (code == null || code.isBlank()) {
			throw HttpError.forbidden("Two-factor authentication is enabled, a code of your authenticator app, a code by email or a recovery code is required");
		}
		boolean valid = RecoveryCodes.looksLikeCode(code) ? this.verifyRecoveryCode(security, code) : this.verifyTotp(security, code) || this.verifyStepUpCode(security, code);
		if (!valid) {
			this.failure(address, username, "confirmation");
			throw HttpError.forbidden("The code is not valid");
		}
		this.throttle.success(address, username);
	}
	//endregion
	
	//region Two-factor setup
	
	/**
	 * Starts setting up an authenticator app, the secret is only stored once a code of the app confirms it.<br>
	 */
	@NonNull
	public TotpSetup beginTotp(@NonNull String username) {
		this.limitEntries(this.pendingTotp);
		String secret = Totp.generateSecret();
		this.pendingTotp.put(username, new PendingTotp(secret, Instant.now().plus(SETUP_LIFETIME)));
		return new TotpSetup(secret, Totp.uri(ISSUER, this.account(username), secret));
	}
	
	/**
	 * Enables the authenticator app set up with {@link #beginTotp}.<br>
	 *
	 * @return New recovery codes if the user had none, otherwise null
	 */
	@Nullable
	public List<String> enableTotp(@NonNull String username, @NonNull String address, @Nullable String code) throws SqlException {
		this.throttle.check(address, username);
		PendingTotp pending = this.pendingTotp.get(username);
		if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
			throw HttpError.badRequest("Start the setup of the authenticator app again");
		}
		long step = Totp.verify(pending.secret(), code, Instant.now(), -1);
		if (step < 0) {
			this.failure(address, username, MfaMethod.TOTP.id());
			throw HttpError.badRequest("The code is not valid, check the time of your device and try again");
		}
		this.pendingTotp.remove(username);
		this.users.setTotp(username, this.pepper.encrypt(TOTP_PURPOSE, pending.secret()), step);
		this.audit.record(username, AuditService.MFA_ENABLE, null, username, address, MfaMethod.TOTP.id());
		return this.ensureRecoveryCodes(username);
	}
	
	/**
	 * Enables codes by email, the user must have a verified email address.<br>
	 *
	 * @return New recovery codes if the user had none, otherwise null
	 */
	@Nullable
	public List<String> enableEmailMfa(@NonNull String username, @NonNull String address) throws SqlException {
		this.requireMail();
		if (this.users.security(username).email() == null) {
			throw HttpError.badRequest("Add and verify an email address first");
		}
		this.users.setEmailMfa(username, true);
		this.audit.record(username, AuditService.MFA_ENABLE, null, username, address, MfaMethod.EMAIL.id());
		return this.ensureRecoveryCodes(username);
	}
	
	/**
	 * Disables one second factor, the recovery codes are removed with the last one.<br>
	 */
	public void disable(@NonNull String username, @NonNull String address, @NonNull MfaMethod method) throws SqlException {
		switch (method) {
			case TOTP -> this.users.setTotp(username, null, -1);
			case EMAIL -> this.users.setEmailMfa(username, false);
			case RECOVERY -> throw HttpError.badRequest("Recovery codes are removed with the last second factor");
		}
		if (!this.users.security(username).mfaEnabled()) {
			this.users.setRecoveryCodes(username, null);
		}
		this.auth.invalidateCache();
		this.audit.record(username, AuditService.MFA_DISABLE, null, username, address, method.id());
	}
	
	@Nullable
	private List<String> ensureRecoveryCodes(@NonNull String username) throws SqlException {
		this.auth.invalidateCache();
		if (RecoveryCodes.remaining(this.users.security(username).recoveryCodes()) > 0) {
			return null;
		}
		List<String> codes = RecoveryCodes.generate();
		this.users.setRecoveryCodes(username, RecoveryCodes.hashAll(this.pepper, codes));
		return codes;
	}
	
	/**
	 * Replaces all recovery codes of the user with new ones.<br>
	 */
	@NonNull
	public List<String> regenerateRecoveryCodes(@NonNull String username, @NonNull String address) throws SqlException {
		if (!this.users.security(username).mfaEnabled()) {
			throw HttpError.badRequest("Recovery codes are only available with two-factor authentication");
		}
		List<String> codes = RecoveryCodes.generate();
		this.users.setRecoveryCodes(username, RecoveryCodes.hashAll(this.pepper, codes));
		this.audit.record(username, AuditService.MFA_RECOVERY_REGENERATE, null, username, address, null);
		return codes;
	}
	
	/**
	 * Turns off two-factor authentication of a user that lost access to every factor, done by an administrator.<br>
	 */
	public void reset(@NonNull String username) throws SqlException {
		this.users.resetMfa(username);
		this.pendingTotp.remove(username);
		this.stepUpCodes.remove(username);
		this.challenges.values().removeIf(challenge -> challenge.username().equals(username));
		this.auth.invalidateCache();
	}
	//endregion
	
	//region Email address
	
	/**
	 * @throws HttpError 409 if another account has the email address
	 */
	private void requireUnusedEmail(@NonNull String username, @NonNull String email) throws SqlException {
		UserSecurityEntity owner = this.users.findByEmail(email);
		if (owner != null && !owner.username().equals(username)) {
			throw HttpError.conflict("The email address is used by another account");
		}
	}
	
	/**
	 * Sends a verification code to a new email address, the address is stored once the code confirms it.<br>
	 */
	public void requestEmailChange(@NonNull String username, @Nullable String email) throws SqlException {
		this.requireMail();
		String normalized = normalizeEmail(email);
		UserSecurityEntity owner = this.users.findByEmail(normalized);
		if (owner != null && owner.username().equals(username)) {
			throw HttpError.conflict("This is already your email address");
		}
		this.requireUnusedEmail(username, normalized);
		PendingEmail previous = this.pendingEmails.get(username);
		Instant now = Instant.now();
		if (previous != null && previous.sentAt().plus(MAIL_COOLDOWN).isAfter(now)) {
			throw HttpError.of(429, "A code was just sent, wait a moment before requesting another one");
		}
		this.limitEntries(this.pendingEmails);
		String code = sixDigits();
		this.pendingEmails.put(username, new PendingEmail(normalized, this.hashCode(code), now, now.plus(this.codeLifetime()), new int[1]));
		this.send(normalized, this.mails.emailVerification(username, code, this.config.mailCodeMinutes()));
	}
	
	/**
	 * Stores the email address of {@link #requestEmailChange} if the code is correct.<br>
	 *
	 * @return The verified email address
	 */
	@NonNull
	public String confirmEmailChange(@NonNull String username, @NonNull String address, @Nullable String code) throws SqlException {
		this.throttle.check(address, username);
		PendingEmail pending = this.pendingEmails.get(username);
		if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
			throw HttpError.badRequest("The code expired, request a new one");
		}
		if (!this.codeMatches(pending.codeHash(), code)) {
			this.failure(address, username, "email-verification");
			synchronized (pending) {
				if (++pending.attempts()[0] >= MAX_ATTEMPTS) {
					this.pendingEmails.remove(username);
					throw HttpError.badRequest("Too many wrong codes, request a new one");
				}
			}
			throw HttpError.badRequest("The code is not valid");
		}
		this.pendingEmails.remove(username);
		String previous = this.users.security(username).email();
		this.requireUnusedEmail(username, pending.email());
		try {
			this.users.setEmail(username, pending.email());
		} catch (SqlException e) {
			// Another account verified the address between the check and the update
			this.requireUnusedEmail(username, pending.email());
			throw e;
		}
		this.audit.record(username, AuditService.EMAIL_CHANGE, null, username, address, previous == null ? "added" : "changed");
		if (previous != null && !previous.equals(pending.email()) && this.mail.available()) {
			// Tell the old address, a stolen session must not silently redirect the password recovery
			this.mailExecutor.execute(() -> this.sendQuietly(previous, this.mails.emailChanged(username, maskEmail(pending.email()))));
		}
		return pending.email();
	}
	
	/**
	 * Removes the email address, not possible while codes by email are the second factor.<br>
	 */
	public void removeEmail(@NonNull String username, @NonNull String address) throws SqlException {
		UserSecurityEntity security = this.users.security(username);
		if (security.emailMfaEnabled()) {
			throw HttpError.badRequest("Disable codes by email before removing the email address");
		}
		this.users.setEmail(username, null);
		this.pendingEmails.remove(username);
		this.audit.record(username, AuditService.EMAIL_CHANGE, null, username, address, "removed");
	}
	//endregion
	
	//region Password recovery
	private void sendQuietly(@NonNull String to, @NonNull Mail mail) {
		try {
			this.mail.send(to, mail);
		} catch (MailSender.MailException e) {
			LOGGER.warn("Failed to send mail '{}'", mail.subject(), e);
		}
	}
	
	/**
	 * Sends a password reset mail if the user name or email address belongs to an account with a verified email address.<br>
	 * The caller always gets the same answer, so it does not tell which accounts exist or have an email address.<br>
	 */
	public void requestPasswordReset(@NonNull String address, @Nullable String login) throws SqlException {
		this.requireMail();
		if (login == null || login.isBlank() || login.length() > 320) {
			throw HttpError.badRequest("Enter your user name or email address");
		}
		String value = login.strip();
		UserSecurityEntity security = value.contains("@") ? this.users.findByEmail(value) : null;
		if (security == null) {
			UserEntity user = this.users.get(value);
			security = user == null ? null : this.users.security(user.username());
		}
		if (security == null || security.email() == null) {
			this.audit.record(null, AuditService.PASSWORD_RESET_REQUEST, null, value, address, "no account with an email address");
			return;
		}
		String username = security.username();
		Instant now = Instant.now();
		Instant last = this.resetMails.get(username);
		if (last != null && last.plus(MAIL_COOLDOWN).isAfter(now)) {
			return;
		}
		if (this.resets.size() >= MAX_ENTRIES) {
			this.cleanup();
			if (this.resets.size() >= MAX_ENTRIES) {
				// Answered like every other request, an error here would tell that the account exists and has an email address
				LOGGER.warn("Too many pending password resets, no reset mail is sent to user '{}'", username);
				return;
			}
		}
		this.resetMails.put(username, now);
		// One reset link per user, a new request replaces the previous link
		this.resets.values().removeIf(reset -> reset.username().equals(username));
		String token = PasswordHasher.randomSecret(32);
		this.resets.put(UserService.hashToken(token), new PasswordReset(username, now.plus(Duration.ofMinutes(this.config.passwordResetMinutes())), new int[1]));
		this.audit.record(null, AuditService.PASSWORD_RESET_REQUEST, null, username, address, null);
		
		Mail mail = this.mails.passwordReset(username, token, this.config.passwordResetMinutes());
		String to = security.email();
		this.mailExecutor.execute(() -> this.sendQuietly(to, mail));
	}
	
	@NonNull
	private PasswordReset resetFor(@Nullable String token) {
		PasswordReset reset = token == null || token.isBlank() ? null : this.resets.get(UserService.hashToken(token.strip()));
		if (reset == null || reset.expired(Instant.now())) {
			throw HttpError.badRequest("The reset link is invalid or expired, request a new one");
		}
		return reset;
	}
	
	/**
	 * Returns which second factor completing the reset requires.<br>
	 */
	@NonNull
	public ResetInfo checkPasswordReset(@Nullable String token) throws SqlException {
		PasswordReset reset = this.resetFor(token);
		UserSecurityEntity security = this.users.security(reset.username());
		// The mail proves access to the email address, an authenticator app is a second factor beyond it
		return new ResetInfo(reset.username(), security.totpEnabled());
	}
	
	/**
	 * Sets a new password with a reset token, ends all sessions of the user and optionally revokes all of its access tokens.<br>
	 * A user with an authenticator app also needs a code of it or a recovery code, the reset mail alone is not enough.<br>
	 *
	 * @return The user name
	 */
	@NonNull
	public String completePasswordReset(@NonNull String address, @Nullable String token, @NonNull String password, @Nullable String code, boolean revokeTokens) throws SqlException {
		PasswordReset reset = this.resetFor(token);
		String username = reset.username();
		this.throttle.check(address, username);
		UserSecurityEntity security = this.users.security(username);
		if (security.totpEnabled()) {
			boolean valid = RecoveryCodes.looksLikeCode(code) ? this.verifyRecoveryCode(security, code) : this.verifyTotp(security, code);
			if (!valid) {
				this.failure(address, username, "password-reset");
				synchronized (reset) {
					if (++reset.attempts()[0] >= MAX_ATTEMPTS) {
						this.resets.remove(UserService.hashToken(Objects.requireNonNull(token).strip()));
						throw HttpError.badRequest("Too many wrong codes, request a new reset link");
					}
				}
				throw HttpError.badRequest("The code of your authenticator app or the recovery code is not valid");
			}
		}
		if (this.resets.remove(UserService.hashToken(Objects.requireNonNull(token).strip())) == null) {
			throw HttpError.badRequest("The reset link is invalid or expired, request a new one");
		}
		this.users.update(username, password, null);
		this.users.deleteSessions(username, null);
		if (revokeTokens) {
			// After a takeover the tokens the attacker created would otherwise stay valid
			this.users.deleteTokens(username).forEach(this.tokenUsage::invalidate);
		}
		this.auth.invalidateCache();
		this.throttle.success(address, username);
		this.audit.record(username, AuditService.PASSWORD_RESET, null, username, address, revokeTokens ? "tokens revoked" : null);
		return username;
	}
	//endregion
	
	public enum MfaMethod {
		TOTP("totp"), EMAIL("email"), RECOVERY("recovery");
		
		private final String id;
		
		MfaMethod(@NonNull String id) {
			this.id = id;
		}
		
		@Nullable
		public static MfaMethod parse(@Nullable String value) {
			for (MfaMethod method : values()) {
				if (method.id.equalsIgnoreCase(value == null ? "" : value.strip())) {
					return method;
				}
			}
			return null;
		}
		
		@NonNull
		public String id() {
			return this.id;
		}
	}
	
	/**
	 * The second step of a sign in.<br>
	 *
	 * @param id The secret identifying the challenge, sent with the code
	 * @param methods The ids of the methods that can complete it
	 * @param emailHint The masked email address codes are sent to, null if codes by email are not enabled
	 */
	public static final class Challenge {
		
		private final String id;
		private final String username;
		private final boolean remember;
		private final Instant expiresAt;
		private final List<String> methods;
		private final String emailHint;
		private int attempts;
		private int mailsSent;
		private Instant lastMailAt;
		private String emailCodeHash;
		private Instant emailCodeExpiresAt;
		
		private Challenge(@NonNull String id, @NonNull String username, boolean remember, @NonNull Instant expiresAt, @NonNull List<String> methods, @Nullable String emailHint) {
			this.id = id;
			this.username = username;
			this.remember = remember;
			this.expiresAt = expiresAt;
			this.methods = methods;
			this.emailHint = emailHint;
		}
		
		private boolean expired(@NonNull Instant now) {
			return this.expiresAt.isBefore(now);
		}
		
		@NonNull
		public String id() {
			return this.id;
		}
		
		@NonNull
		public String username() {
			return this.username;
		}
		
		public boolean remember() {
			return this.remember;
		}
		
		@NonNull
		public Instant expiresAt() {
			return this.expiresAt;
		}
		
		@NonNull
		public List<String> methods() {
			return this.methods;
		}
		
		@Nullable
		public String emailHint() {
			return this.emailHint;
		}
	}
	
	public record TotpSetup(@NonNull String secret, @NonNull String uri) {}
	
	public record ResetInfo(@NonNull String username, boolean totpRequired) {}
	
	private record PendingTotp(@NonNull String secret, @NonNull Instant expiresAt) {}
	
	private record StepUpCode(@NonNull String codeHash, @NonNull Instant sentAt, @NonNull Instant expiresAt, int @NonNull [] attempts) {}
	
	private record PendingEmail(@NonNull String email, @NonNull String codeHash, @NonNull Instant sentAt, @NonNull Instant expiresAt, int @NonNull [] attempts) {}
	
	private record PasswordReset(@NonNull String username, @NonNull Instant expiresAt, int @NonNull [] attempts) {
		
		private boolean expired(@NonNull Instant now) {
			return this.expiresAt.isBefore(now);
		}
	}
}
