package net.luis.artifactory.repository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.bcpg.*;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.bc.BcPGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.operator.bc.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * The OpenPGP key that signs the indexes ({@code InRelease}, {@code Release.gpg}) of all Debian repositories.<br>
 * <p>
 *     APT refuses unsigned repositories unless they are marked {@code trusted=yes}, so every repository is signed.
 *     The key is read from the environment, or from a file generated into the storage directory on first use.
 * </p>
 */
public final class DebianSigningKey {

	private static final Logger LOGGER = LogManager.getLogger(DebianSigningKey.class);
	public static final String FILE_NAME = "debian-signing-key.asc";
	private static final String USER_ID = "LArtifactory Debian repository signing key";
	private final PGPPrivateKey privateKey;
	private final PGPPublicKey signingKey;
	private final PGPPublicKeyRing publicRing;

	private DebianSigningKey(@NonNull PGPPrivateKey privateKey, @NonNull PGPPublicKey signingKey, @NonNull PGPPublicKeyRing publicRing) {
		this.privateKey = privateKey;
		this.signingKey = signingKey;
		this.publicRing = publicRing;
	}

	/**
	 * Loads the configured key, or the key stored in the storage directory, which is generated if it does not exist yet.<br>
	 *
	 * @param configured An armored secret key, or the path of a file containing one, null to use the generated key
	 * @param passphrase The passphrase of the configured key, null if it is not protected
	 * @param storagePath The storage directory holding the generated key
	 */
	@NonNull
	public static DebianSigningKey load(@Nullable String configured, @Nullable String passphrase, @NonNull Path storagePath) {
		try {
			if (configured != null) {
				String armored = configured.strip().startsWith("-----BEGIN") ? configured : Files.readString(Path.of(configured.strip()), StandardCharsets.US_ASCII);
				return parse(armored, passphrase);
			}
			Path file = storagePath.resolve(FILE_NAME);
			if (Files.exists(file)) {
				return parse(Files.readString(file, StandardCharsets.US_ASCII), null);
			}
			Files.createDirectories(storagePath);
			String armored = generate();
			write(file, armored);
			LOGGER.warn("No Debian signing key configured, generated one in {}. Back it up, clients have to import a new public key if it changes. " +
				"To keep it in the environment instead, set {} to the content or the path of that file", file, "ARTIFACTORY_DEBIAN_SIGNING_KEY");
			return parse(armored, null);
		} catch (IOException | PGPException e) {
			throw new IllegalStateException("Failed to load the Debian signing key", e);
		}
	}

	@NonNull
	private static DebianSigningKey parse(@NonNull String armored, @Nullable String passphrase) throws IOException, PGPException {
		PGPSecretKeyRingCollection rings;
		try (InputStream input = PGPUtil.getDecoderStream(new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII)))) {
			rings = new BcPGPSecretKeyRingCollection(input);
		}
		char[] password = passphrase == null ? new char[0] : passphrase.toCharArray();
		for (PGPSecretKeyRing ring : rings) {
			for (PGPSecretKey key : ring) {
				if (!key.isSigningKey() || key.isPrivateKeyEmpty()) {
					continue;
				}
				PGPPrivateKey privateKey = key.extractPrivateKey(new BcPBESecretKeyDecryptorBuilder(new BcPGPDigestCalculatorProvider()).build(password));
				List<PGPPublicKey> publicKeys = new ArrayList<>();
				ring.getPublicKeys().forEachRemaining(publicKeys::add);
				return new DebianSigningKey(privateKey, key.getPublicKey(), new PGPPublicKeyRing(publicKeys));
			}
		}
		throw new PGPException("The Debian signing key contains no secret key that can sign");
	}

	/**
	 * Generates an RSA 3072 key that can sign and certify, without passphrase, armored.<br>
	 */
	@NonNull
	private static String generate() throws IOException, PGPException {
		RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
		generator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 3072, 80));
		AsymmetricCipherKeyPair pair = generator.generateKeyPair();
		PGPKeyPair keyPair = new BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, pair, new Date());

		PGPSignatureSubpacketGenerator hashed = new PGPSignatureSubpacketGenerator();
		hashed.setKeyFlags(false, KeyFlags.SIGN_DATA | KeyFlags.CERTIFY_OTHER);
		hashed.setPreferredHashAlgorithms(false, new int[] { HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA256 });
		hashed.setIssuerFingerprint(false, keyPair.getPublicKey());
		PGPKeyRingGenerator ringGenerator = new PGPKeyRingGenerator(
			PGPSignature.POSITIVE_CERTIFICATION, keyPair, USER_ID,
			new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1), hashed.generate(), null,
			new BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA512), null
		);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ArmoredOutputStream armored = new ArmoredOutputStream(output)) {
			ringGenerator.generateSecretKeyRing().encode(armored);
		}
		return output.toString(StandardCharsets.US_ASCII);
	}

	private static void write(@NonNull Path file, @NonNull String content) throws IOException {
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			Files.writeString(file, content, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} else {
			Files.writeString(file, content, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		}
	}

	private static void escapeLine(@NonNull StringBuilder builder, @NonNull String line) {
		// Dash escaping of the cleartext signature framework (RFC 4880 7.1)
		if (line.startsWith("-")) {
			builder.append("- ");
		}
		builder.append(line).append('\n');
	}

	@NonNull
	private static String stripTrailingWhitespace(@NonNull String line) {
		int end = line.length();
		while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t' || line.charAt(end - 1) == '\r')) {
			end--;
		}
		return line.substring(0, end);
	}

	/**
	 * The creation time of the signing key, a signature must not be older than it.<br>
	 */
	@NonNull
	public Instant createdAt() {
		return this.signingKey.getCreationTime().toInstant();
	}

	/**
	 * Returns the public key, armored, for clients to import into their keyring.<br>
	 */
	@NonNull
	public String armoredPublicKey() {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (ArmoredOutputStream armored = new ArmoredOutputStream(output)) {
				this.publicRing.encode(armored, true);
			}
			return output.toString(StandardCharsets.US_ASCII);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Creates a detached armored signature of the data ({@code Release.gpg}).<br>
	 *
	 * @param time The creation time of the signature, makes the signature a function of the signed content
	 */
	@NonNull
	public String detachedSignature(byte @NonNull [] data, @NonNull Instant time) {
		PGPSignatureGenerator generator = this.generator(PGPSignature.BINARY_DOCUMENT, time);
		generator.update(data);
		return this.armor(generator);
	}

	/**
	 * Creates a cleartext signed document ({@code InRelease}).<br>
	 */
	@NonNull
	public String clearSign(@NonNull String text, @NonNull Instant time) {
		PGPSignatureGenerator generator = this.generator(PGPSignature.CANONICAL_TEXT_DOCUMENT, time);
		List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
		// The line ending of the last line belongs to the framework, not to the signed text
		if (!lines.isEmpty() && lines.getLast().isEmpty()) {
			lines.removeLast();
		}
		StringBuilder builder = new StringBuilder("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\n");
		for (int i = 0; i < lines.size(); i++) {
			String line = stripTrailingWhitespace(lines.get(i));
			if (i > 0) {
				generator.update("\r\n".getBytes(StandardCharsets.UTF_8));
			}
			generator.update(line.getBytes(StandardCharsets.UTF_8));
			escapeLine(builder, line);
		}
		builder.append(this.armor(generator));
		return builder.toString();
	}

	@NonNull
	private PGPSignatureGenerator generator(int type, @NonNull Instant time) {
		try {
			PGPSignatureGenerator generator = new PGPSignatureGenerator(new BcPGPContentSignerBuilder(this.signingKey.getAlgorithm(), HashAlgorithmTags.SHA512), this.signingKey);
			generator.init(type, this.privateKey);
			PGPSignatureSubpacketGenerator hashed = new PGPSignatureSubpacketGenerator();
			// A signature older than its key is rejected by gpgv, so it is never dated before the key was created
			Instant created = time.isBefore(this.createdAt()) ? this.createdAt() : time;
			hashed.setSignatureCreationTime(false, Date.from(created));
			hashed.setIssuerFingerprint(false, this.signingKey);
			generator.setHashedSubpackets(hashed.generate());
			PGPSignatureSubpacketGenerator unhashed = new PGPSignatureSubpacketGenerator();
			unhashed.setIssuerKeyID(false, this.signingKey.getKeyID());
			generator.setUnhashedSubpackets(unhashed.generate());
			return generator;
		} catch (PGPException e) {
			throw new IllegalStateException("Failed to initialize the Debian signing key", e);
		}
	}

	@NonNull
	private String armor(@NonNull PGPSignatureGenerator generator) {
		try {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			try (ArmoredOutputStream armored = new ArmoredOutputStream(output)) {
				generator.generate().encode(armored);
			}
			return output.toString(StandardCharsets.US_ASCII);
		} catch (IOException | PGPException e) {
			throw new IllegalStateException("Failed to sign with the Debian signing key", e);
		}
	}
}
