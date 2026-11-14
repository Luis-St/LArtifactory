package net.luis.artifactory.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryCodesTest {
	
	private static final Pepper PEPPER = new Pepper("pepper-for-the-recovery-code-test!!".getBytes(StandardCharsets.UTF_8));
	
	@Test
	void generatesEightDistinctCodesOfTheFormat() {
		List<String> codes = RecoveryCodes.generate();
		assertEquals(8, codes.size());
		assertEquals(8, new HashSet<>(codes).size());
		codes.forEach(code -> assertTrue(code.matches("[A-Z]{4}-[A-Z]{4}"), code));
	}
	
	@Test
	void normalizesInput() {
		assertEquals("ABCD-EFGH", RecoveryCodes.normalize(" abcd efgh "));
		assertEquals("ABCD-EFGH", RecoveryCodes.normalize("abcd-efgh"));
		assertNull(RecoveryCodes.normalize("123456"));
		assertNull(RecoveryCodes.normalize("ABCD-EFG1"));
		assertNull(RecoveryCodes.normalize(null));
	}
	
	@Test
	void consumesEachCodeOnce() {
		List<String> codes = RecoveryCodes.generate();
		String stored = RecoveryCodes.hashAll(PEPPER, codes);
		assertFalse(stored.contains(codes.getFirst()), "codes must only be stored hashed");
		assertEquals(8, RecoveryCodes.remaining(stored));
		
		String remaining = RecoveryCodes.consume(PEPPER, stored, codes.getFirst().toLowerCase());
		assertNotNull(remaining);
		assertEquals(7, RecoveryCodes.remaining(remaining));
		assertNull(RecoveryCodes.consume(PEPPER, remaining, codes.getFirst()));
		assertNull(RecoveryCodes.consume(PEPPER, stored, "ZZZZ-ZZZZ"));
		assertEquals(0, RecoveryCodes.remaining(null));
	}
}
