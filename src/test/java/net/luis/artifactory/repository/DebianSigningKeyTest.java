package net.luis.artifactory.repository;

import org.bouncycastle.bcpg.*;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.operator.bc.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Date;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DebianSigningKeyTest {
	
	private static PGPPublicKey publicKey(DebianSigningKey key) throws IOException, PGPException {
		try (InputStream input = PGPUtil.getDecoderStream(new ByteArrayInputStream(key.armoredPublicKey().getBytes(StandardCharsets.US_ASCII)))) {
			return new BcPGPPublicKeyRingCollection(input).getKeyRings().next().getPublicKey();
		}
	}
	
	private static PGPSignature signature(String armored) throws IOException {
		try (InputStream input = PGPUtil.getDecoderStream(new ByteArrayInputStream(armored.getBytes(StandardCharsets.US_ASCII)))) {
			return ((PGPSignatureList) new BcPGPObjectFactory(input).nextObject()).get(0);
		}
	}
	
	@Test
	void generatedKeyIsStoredAndReused(@TempDir Path storage) throws Exception {
		DebianSigningKey first = DebianSigningKey.load(null, null, storage);
		assertTrue(Files.exists(storage.resolve(DebianSigningKey.FILE_NAME)));
		DebianSigningKey second = DebianSigningKey.load(null, null, storage);
		assertEquals(first.armoredPublicKey(), second.armoredPublicKey());
		DebianSigningKey configured = DebianSigningKey.load(storage.resolve(DebianSigningKey.FILE_NAME).toString(), null, storage.resolve("other"));
		assertEquals(first.armoredPublicKey(), configured.armoredPublicKey(), "a configured path is read like the generated file");
	}
	
	@Test
	void signaturesVerify(@TempDir Path storage) throws Exception {
		DebianSigningKey key = DebianSigningKey.load(null, null, storage);
		PGPPublicKey publicKey = publicKey(key);
		byte[] data = "Origin: test\nSuite: stable\n".getBytes(StandardCharsets.UTF_8);
		
		PGPSignature detached = signature(key.detachedSignature(data, Instant.now()));
		detached.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
		detached.update(data);
		assertTrue(detached.verify());
		
		String clearSigned = key.clearSign("Origin: test\nSuite: stable\n", Instant.now());
		assertTrue(clearSigned.startsWith("-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA512\n\nOrigin: test\nSuite: stable\n-----BEGIN PGP SIGNATURE-----"));
		PGPSignature inline = signature(clearSigned.substring(clearSigned.indexOf("-----BEGIN PGP SIGNATURE-----")));
		inline.init(new BcPGPContentVerifierBuilderProvider(), publicKey);
		inline.update("Origin: test\r\nSuite: stable".getBytes(StandardCharsets.UTF_8));
		assertTrue(inline.verify());
	}
	
	@Test
	void signsWithTheSigningSubkey(@TempDir Path storage) throws Exception {
		// A primary key that may only certify and a subkey that may sign, as created by gpg --quick-gen-key ... cert
		PGPKeyPair primary = rsaKeyPair();
		PGPKeyPair subkey = rsaKeyPair();
		PGPSignatureSubpacketGenerator primaryFlags = new PGPSignatureSubpacketGenerator();
		primaryFlags.setKeyFlags(false, KeyFlags.CERTIFY_OTHER);
		PGPKeyRingGenerator generator = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, primary, "certify only",
			new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1), primaryFlags.generate(), null,
			new BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA512), null);
		PGPSignatureSubpacketGenerator subkeyFlags = new PGPSignatureSubpacketGenerator();
		subkeyFlags.setKeyFlags(false, KeyFlags.SIGN_DATA);
		generator.addSubKey(subkey, subkeyFlags.generate(), null, new BcPGPContentSignerBuilder(PublicKeyAlgorithmTags.RSA_GENERAL, HashAlgorithmTags.SHA512));
		ByteArrayOutputStream armored = new ByteArrayOutputStream();
		try (ArmoredOutputStream output = new ArmoredOutputStream(armored)) {
			generator.generateSecretKeyRing().encode(output);
		}
		
		DebianSigningKey key = DebianSigningKey.load(armored.toString(StandardCharsets.US_ASCII), null, storage);
		PGPSignature signature = signature(key.detachedSignature(new byte[] { 1 }, Instant.now()));
		assertEquals(subkey.getKeyID(), signature.getKeyID(), "the subkey with the sign flag signs, not the certify only primary key");
	}
	
	private static PGPKeyPair rsaKeyPair() throws PGPException {
		RSAKeyPairGenerator generator = new RSAKeyPairGenerator();
		generator.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 80));
		return new BcPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, generator.generateKeyPair(), new Date());
	}
	
	@Test
	void existingKeysAreLoadedAtStartup(@TempDir Path storage) throws Exception {
		assertNull(DebianSigningKey.loadExisting(null, null, storage), "a key that does not exist yet is generated on first use");
		DebianSigningKey.load(null, null, storage.resolve("generated"));
		String generated = Files.readString(storage.resolve("generated").resolve(DebianSigningKey.FILE_NAME));
		assertNotNull(DebianSigningKey.loadExisting(generated, null, storage));
		assertThrows(IllegalStateException.class, () -> DebianSigningKey.loadExisting("-----BEGIN PGP PRIVATE KEY BLOCK-----\n\nbroken\n-----END PGP PRIVATE KEY BLOCK-----\n", null, storage));
	}
	
	@Test
	void signatureIsNotOlderThanTheKey(@TempDir Path storage) throws Exception {
		DebianSigningKey key = DebianSigningKey.load(null, null, storage);
		PGPSignature signature = signature(key.detachedSignature(new byte[] { 1 }, Instant.EPOCH));
		assertFalse(signature.getCreationTime().toInstant().isBefore(key.createdAt()));
	}
}
