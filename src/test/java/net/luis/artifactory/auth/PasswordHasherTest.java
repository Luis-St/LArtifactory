package net.luis.artifactory.auth;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
	
	private static final PasswordHasher HASHER = new PasswordHasher(new Pepper("pepper-for-the-password-hasher-test".getBytes(StandardCharsets.UTF_8)));
	
	@Test
	void verifiesHashedPassword() {
		String hash = HASHER.hash("correct horse battery staple");
		assertTrue(hash.startsWith("pbkdf2p$"));
		assertTrue(HASHER.verify("correct horse battery staple", hash));
		assertFalse(HASHER.verify("wrong", hash));
		assertFalse(HASHER.needsRehash(hash));
	}
	
	@Test
	void hashesAreSalted() {
		assertNotEquals(HASHER.hash("password"), HASHER.hash("password"));
	}
	
	@Test
	void hashesArePeppered() {
		String hash = HASHER.hash("password");
		PasswordHasher other = new PasswordHasher(new Pepper("another-pepper-of-the-same-length!!".getBytes(StandardCharsets.UTF_8)));
		assertFalse(other.verify("password", hash), "a hash must not verify without its pepper");
	}
	
	@Test
	void verifiesLegacyHashesAndAsksForRehash() throws Exception {
		byte[] salt = new byte[16];
		byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec("password".toCharArray(), salt, 1000, 256)).getEncoded();
		String legacy = "pbkdf2$1000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
		assertTrue(HASHER.verify("password", legacy));
		assertFalse(HASHER.verify("other", legacy));
		assertTrue(HASHER.needsRehash(legacy));
	}
	
	@Test
	void rejectsMalformedHashes() {
		assertFalse(HASHER.verify("password", "plain"));
		assertFalse(HASHER.verify("password", "pbkdf2$x$y$z"));
		assertFalse(HASHER.verify("password", "pbkdf2p$0$AAAA$AAAA"));
	}
	
	@Test
	void accessLevels() {
		assertTrue(AccessLevel.DELETE.includes(AccessLevel.WRITE));
		assertTrue(AccessLevel.WRITE.includes(AccessLevel.READ));
		assertFalse(AccessLevel.READ.includes(AccessLevel.WRITE));
		assertEquals(AccessLevel.READ, AccessLevel.min(AccessLevel.DELETE, AccessLevel.READ));
		assertEquals(AccessLevel.WRITE, AccessLevel.parse(" write "));
		assertNull(AccessLevel.parse("admin"));
	}
}
