package net.luis.artifactory.auth;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;

/**
 * The server secret that is kept outside the database (the pepper).<br>
 * <p>
 *     Password hashes, recovery code hashes and the encrypted two-factor secrets depend on it, so a leaked database dump alone
 *     neither allows guessing passwords offline nor generating two-factor codes. Each use derives its own key from the pepper
 *     with a purpose label, so a key of one use can not be applied to another.
 * </p>
 * <p>
 *     The pepper is read from the environment, or from a generated file in the storage directory if none is configured.
 *     Losing it makes every password, recovery code and two-factor secret invalid, so it has to be backed up separately from the database.
 * </p>
 */
public final class Pepper {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(Pepper.class);
	private static final String FILE_NAME = "pepper";
	/**
	 * Marks a configured pepper as base64, the format of the generated file.<br>
	 * Without it a configured value is used as its UTF-8 bytes, so a plain text pepper that happens to be valid base64 keeps working.<br>
	 */
	public static final String BASE64_PREFIX = "base64:";
	private static final String ENCRYPTED_PREFIX = "v1:";
	private static final int IV_LENGTH = 12;
	private static final int TAG_BITS = 128;
	private static final SecureRandom RANDOM = new SecureRandom();
	
	private final byte[] secret;
	
	public Pepper(byte @NonNull [] secret) {
		Objects.requireNonNull(secret, "Secret must not be null");
		if (secret.length < 16) {
			throw new IllegalArgumentException("The pepper must have at least 16 bytes");
		}
		this.secret = secret.clone();
	}
	
	/**
	 * Returns the configured pepper, or the pepper stored in the storage directory, which is generated on the first start.<br>
	 * <p>
	 *     A configured pepper is used as its UTF-8 bytes, or decoded like the generated file if it starts with {@link #BASE64_PREFIX}.
	 *     {@code base64:<content of the file>} is therefore the same pepper as the file, with or without the file, and is the way
	 *     to move a generated pepper into the environment. If the storage directory already holds a generated pepper, the configured
	 *     value must resolve to the same secret (the bare content of the file is accepted as well), any other value would silently
	 *     invalidate every stored hash, so the server refuses to start instead.
	 * </p>
	 *
	 * @throws IllegalStateException If a pepper is configured that differs from the generated one
	 */
	@NonNull
	public static Pepper load(@Nullable String configured, @NonNull Path storagePath) {
		Path file = storagePath.resolve(FILE_NAME);
		try {
			if (Files.exists(file)) {
				String stored = Files.readString(file, StandardCharsets.US_ASCII).strip();
				byte[] secret = Base64.getDecoder().decode(stored);
				if (configured != null && !configured.strip().equals(stored) && !MessageDigest.isEqual(decodeConfigured(configured), secret)) {
					throw new IllegalStateException("The configured password pepper differs from the generated one in " + file + ", every password, recovery code and two-factor secret would stop verifying. " +
						"Configure '" + BASE64_PREFIX + "' followed by the content of that file as the pepper, or delete the file if the pepper is meant to change");
				}
				return new Pepper(secret);
			}
			if (configured != null) {
				return new Pepper(decodeConfigured(configured));
			}
			byte[] secret = new byte[32];
			RANDOM.nextBytes(secret);
			Files.createDirectories(storagePath);
			write(file, Base64.getEncoder().encodeToString(secret) + "\n");
			LOGGER.warn("No password pepper configured, generated one in {}. Back it up separately from the database, without it no password, recovery code or two-factor secret can be verified. " +
				"To keep it in the environment instead, set {} to '{}' followed by the content of that file", file, "ARTIFACTORY_PASSWORD_PEPPER", BASE64_PREFIX);
			return new Pepper(secret);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to load the password pepper from " + file, e);
		}
	}
	
	/**
	 * Returns the secret of a configured pepper, decoded if it carries the {@link #BASE64_PREFIX}, otherwise its UTF-8 bytes.<br>
	 *
	 * @throws IllegalArgumentException If the value after the prefix is not valid base64
	 */
	public static byte @NonNull [] decodeConfigured(@NonNull String configured) {
		String value = configured.strip();
		if (value.startsWith(BASE64_PREFIX)) {
			try {
				return Base64.getDecoder().decode(value.substring(BASE64_PREFIX.length()).strip());
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("The password pepper after '" + BASE64_PREFIX + "' is not valid base64", e);
			}
		}
		return configured.getBytes(StandardCharsets.UTF_8);
	}
	
	/**
	 * Creates the file readable only by the owner from the start, on file systems with posix permissions.<br>
	 */
	private static void write(@NonNull Path file, @NonNull String content) throws IOException {
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			Files.writeString(file, content, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} else {
			Files.writeString(file, content, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		}
	}
	
	/**
	 * Computes the keyed hash of the data for the given purpose.<br>
	 */
	public byte @NonNull [] hmac(@NonNull String purpose, byte @NonNull [] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(this.key(purpose), "HmacSHA256"));
			return mac.doFinal(data);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("Failed to compute hmac", e);
		}
	}
	
	@NonNull
	public String hmacHex(@NonNull String purpose, @NonNull String data) {
		return HexFormat.of().formatHex(this.hmac(purpose, data.getBytes(StandardCharsets.UTF_8)));
	}
	
	/**
	 * Encrypts the value with AES-GCM using a key derived for the given purpose.<br>
	 */
	@NonNull
	public String encrypt(@NonNull String purpose, @NonNull String value) {
		try {
			byte[] iv = new byte[IV_LENGTH];
			RANDOM.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(this.key(purpose), "AES"), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
			byte[] result = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, result, 0, iv.length);
			System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
			return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(result);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to encrypt", e);
		}
	}
	
	/**
	 * Decrypts a value of {@link #encrypt}.<br>
	 *
	 * @return The value, null if it is malformed or was encrypted with another pepper or purpose
	 */
	@Nullable
	public String decrypt(@NonNull String purpose, @NonNull String encrypted) {
		if (!encrypted.startsWith(ENCRYPTED_PREFIX)) {
			return null;
		}
		try {
			byte[] data = Base64.getDecoder().decode(encrypted.substring(ENCRYPTED_PREFIX.length()));
			if (data.length <= IV_LENGTH) {
				return null;
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(this.key(purpose), "AES"), new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
			cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException | AEADBadTagException e) {
			return null;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to decrypt", e);
		}
	}
	
	/**
	 * Derives the 256 bit key of a purpose from the pepper.<br>
	 */
	private byte @NonNull [] key(@NonNull String purpose) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(this.secret, "HmacSHA256"));
			return mac.doFinal(("lartifactory:" + purpose).getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("Failed to derive key", e);
		}
	}
}
