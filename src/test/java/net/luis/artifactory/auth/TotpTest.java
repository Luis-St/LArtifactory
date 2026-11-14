package net.luis.artifactory.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
	
	// The sha1 secret of the RFC 6238 test vectors
	private static final String SECRET = Totp.encodeBase32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
	
	@Test
	void matchesRfcTestVectors() {
		// RFC 6238 lists eight digit codes, six digit codes are their last six digits
		assertEquals("287082", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(59))));
		assertEquals("081804", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(1111111109))));
		assertEquals("050471", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(1111111111))));
		assertEquals("005924", Totp.code(SECRET, Totp.step(Instant.ofEpochSecond(1234567890))));
	}
	
	@Test
	void base32RoundTrips() {
		byte[] data = "any secret bytes".getBytes(StandardCharsets.US_ASCII);
		assertArrayEquals(data, Totp.decodeBase32(Totp.encodeBase32(data)));
		assertNull(Totp.decodeBase32("not base32!"));
		assertEquals(32, Totp.generateSecret().length());
	}
	
	@Test
	void acceptsNeighbouringStepsOnce() {
		Instant now = Instant.ofEpochSecond(1_700_000_000);
		long step = Totp.step(now);
		String previous = Totp.code(SECRET, step - 1);
		assertEquals(step - 1, Totp.verify(SECRET, previous, now, -1));
		assertEquals(-1, Totp.verify(SECRET, previous, now, step - 1), "a used code must not be accepted again");
		assertEquals(step, Totp.verify(SECRET, Totp.code(SECRET, step), now, step - 1));
		assertEquals(-1, Totp.verify(SECRET, Totp.code(SECRET, step - 2), now, -1));
		assertEquals(-1, Totp.verify(SECRET, "12345", now, -1));
		assertEquals(-1, Totp.verify(SECRET, null, now, -1));
	}
	
	@Test
	void buildsOtpauthUri() {
		assertEquals("otpauth://totp/LArtifactory:jane%20doe?secret=ABC&issuer=LArtifactory&algorithm=SHA1&digits=6&period=30", Totp.uri("LArtifactory", "jane doe", "ABC"));
	}
}
