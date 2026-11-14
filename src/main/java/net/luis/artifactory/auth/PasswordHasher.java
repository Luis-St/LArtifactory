package net.luis.artifactory.auth;

import org.jspecify.annotations.NonNull;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;

/**
 * Hashes passwords with a random salt per password and the server {@link Pepper}.<br>
 * <p>
 *     The password is first keyed with the pepper (HMAC-SHA256), then stretched with PBKDF2 and a random 16 byte salt.
 *     The salt is stored with the hash and makes equal passwords hash differently, the pepper is not stored in the database,
 *     so a database dump alone can not be used to guess passwords offline.
 * </p>
 * <p>
 *     Hashes have the format {@code pbkdf2p$iterations$salt$hash}. Hashes of the format {@code pbkdf2$...} were created before
 *     the pepper was introduced, they still verify and {@link #needsRehash} reports them so they are replaced on the next login.
 * </p>
 */
public final class PasswordHasher {

	private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final String PEPPERED = "pbkdf2p";
	private static final String LEGACY = "pbkdf2";
	private static final String PEPPER_PURPOSE = "password";
	private static final int ITERATIONS = 210_000;
	private static final int KEY_LENGTH = 256;
	private static final SecureRandom RANDOM = new SecureRandom();

	private final Pepper pepper;

	public PasswordHasher(@NonNull Pepper pepper) {
		this.pepper = Objects.requireNonNull(pepper, "Pepper must not be null");
	}

	@NonNull
	public static String randomSecret(int bytes) {
		byte[] data = new byte[bytes];
		RANDOM.nextBytes(data);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
	}

	private static byte @NonNull [] pbkdf2(char @NonNull [] password, byte @NonNull [] salt, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
			return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException("Failed to hash password", e);
		}
	}

	@NonNull
	public String hash(@NonNull String password) {
		Objects.requireNonNull(password, "Password must not be null");

		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		byte[] hash = pbkdf2(this.peppered(password), salt, ITERATIONS);
		return PEPPERED + "$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
	}

	public boolean verify(@NonNull String password, @NonNull String stored) {
		Objects.requireNonNull(password, "Password must not be null");
		Objects.requireNonNull(stored, "Stored hash must not be null");

		String[] parts = stored.split("\\$");
		if (parts.length != 4 || (!PEPPERED.equals(parts[0]) && !LEGACY.equals(parts[0]))) {
			return false;
		}
		try {
			int iterations = Integer.parseInt(parts[1]);
			if (iterations < 1) {
				return false;
			}
			byte[] salt = Base64.getDecoder().decode(parts[2]);
			byte[] expected = Base64.getDecoder().decode(parts[3]);
			char[] input = PEPPERED.equals(parts[0]) ? this.peppered(password) : password.toCharArray();
			return MessageDigest.isEqual(expected, pbkdf2(input, salt, iterations));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Whether a verified hash should be replaced, because it was created without the pepper or with fewer iterations.<br>
	 */
	public boolean needsRehash(@NonNull String stored) {
		String[] parts = stored.split("\\$");
		if (parts.length != 4 || !PEPPERED.equals(parts[0])) {
			return true;
		}
		try {
			return Integer.parseInt(parts[1]) < ITERATIONS;
		} catch (NumberFormatException e) {
			return true;
		}
	}

	private char @NonNull [] peppered(@NonNull String password) {
		return Base64.getEncoder().encodeToString(this.pepper.hmac(PEPPER_PURPOSE, password.getBytes(StandardCharsets.UTF_8))).toCharArray();
	}
}
