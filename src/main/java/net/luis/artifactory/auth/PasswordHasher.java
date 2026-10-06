package net.luis.artifactory.auth;

import org.jspecify.annotations.NonNull;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;

public final class PasswordHasher {
	
	private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int ITERATIONS = 210_000;
	private static final int KEY_LENGTH = 256;
	private static final SecureRandom RANDOM = new SecureRandom();
	
	private PasswordHasher() {}
	
	public static @NonNull String hash(@NonNull String password) {
		Objects.requireNonNull(password, "Password must not be null");
		
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		byte[] hash = pbkdf2(password, salt, ITERATIONS);
		return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
	}
	
	public static boolean verify(@NonNull String password, @NonNull String stored) {
		Objects.requireNonNull(password, "Password must not be null");
		Objects.requireNonNull(stored, "Stored hash must not be null");
		
		String[] parts = stored.split("\\$");
		if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
			return false;
		}
		try {
			int iterations = Integer.parseInt(parts[1]);
			byte[] salt = Base64.getDecoder().decode(parts[2]);
			byte[] expected = Base64.getDecoder().decode(parts[3]);
			return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
	
	private static byte @NonNull [] pbkdf2(@NonNull String password, byte @NonNull [] salt, int iterations) {
		try {
			PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH);
			return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
		} catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
			throw new IllegalStateException("Failed to hash password", e);
		}
	}
	
	public static @NonNull String randomSecret(int bytes) {
		byte[] data = new byte[bytes];
		RANDOM.nextBytes(data);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
	}
}
