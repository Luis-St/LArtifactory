package net.luis.artifactory.repository;

import org.bouncycastle.openpgp.*;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.bc.BcPGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
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
	void signatureIsNotOlderThanTheKey(@TempDir Path storage) throws Exception {
		DebianSigningKey key = DebianSigningKey.load(null, null, storage);
		PGPSignature signature = signature(key.detachedSignature(new byte[] { 1 }, Instant.EPOCH));
		assertFalse(signature.getCreationTime().toInstant().isBefore(key.createdAt()));
	}
}
