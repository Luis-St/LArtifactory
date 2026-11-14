package net.luis.artifactory.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PepperTest {
	
	@TempDir
	Path storage;
	
	@Test
	void encryptsForOnePurpose() {
		Pepper pepper = new Pepper("pepper-for-the-encryption-test!!!!!".getBytes(StandardCharsets.UTF_8));
		String encrypted = pepper.encrypt("totp", "SECRET");
		assertNotEquals(encrypted, pepper.encrypt("totp", "SECRET"), "every encryption uses a new iv");
		assertEquals("SECRET", pepper.decrypt("totp", encrypted));
		assertNull(pepper.decrypt("other", encrypted));
		assertNull(new Pepper("another-pepper-for-the-encryption!!".getBytes(StandardCharsets.UTF_8)).decrypt("totp", encrypted));
		assertNull(pepper.decrypt("totp", "v1:broken"));
	}
	
	@Test
	void generatesAndReusesStoredPepper() {
		Pepper first = Pepper.load(null, this.storage);
		Pepper second = Pepper.load(null, this.storage);
		assertEquals(first.hmacHex("test", "value"), second.hmacHex("test", "value"));
		assertNotEquals(first.hmacHex("test", "value"), first.hmacHex("other", "value"));
	}
}
