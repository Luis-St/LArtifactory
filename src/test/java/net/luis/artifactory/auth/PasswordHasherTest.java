package net.luis.artifactory.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

	@Test
	void verifiesHashedPassword() {
		String hash = PasswordHasher.hash("correct horse battery staple");
		assertTrue(PasswordHasher.verify("correct horse battery staple", hash));
		assertFalse(PasswordHasher.verify("wrong", hash));
	}

	@Test
	void hashesAreSalted() {
		assertNotEquals(PasswordHasher.hash("password"), PasswordHasher.hash("password"));
	}

	@Test
	void rejectsMalformedHashes() {
		assertFalse(PasswordHasher.verify("password", "plain"));
		assertFalse(PasswordHasher.verify("password", "pbkdf2$x$y$z"));
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
