package net.luis.artifactory.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

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

	@Test
	void configuredPepperMustMatchGeneratedOne() throws Exception {
		Pepper generated = Pepper.load(null, this.storage);
		String content = Files.readString(this.storage.resolve("pepper"));
		assertEquals(generated.hmacHex("test", "value"), Pepper.load(content.strip(), this.storage).hmacHex("test", "value"), "the file content moved into the environment keeps the pepper");
		assertThrows(IllegalStateException.class, () -> Pepper.load("a-different-pepper-of-at-least-32-chars", this.storage));
	}

	@Test
	void configuredPepperWithoutFileIsUsedAsIs() {
		Pepper configured = Pepper.load("a-configured-pepper-of-at-least-32-chars", this.storage);
		assertEquals(new Pepper("a-configured-pepper-of-at-least-32-chars".getBytes(StandardCharsets.UTF_8)).hmacHex("test", "value"), configured.hmacHex("test", "value"));
		assertFalse(Files.exists(this.storage.resolve("pepper")), "a configured pepper is not written to the storage");
	}

	@Test
	void generatedPepperIsOnlyReadableByTheOwner() throws Exception {
		Pepper.load(null, this.storage);
		Path file = this.storage.resolve("pepper");
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
		}
	}
}
