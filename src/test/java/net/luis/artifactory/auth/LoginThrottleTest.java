package net.luis.artifactory.auth;

import net.luis.artifactory.http.HttpError;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class LoginThrottleTest {
	
	private final MutableClock clock = new MutableClock();
	private final LoginThrottle throttle = new LoginThrottle(3, Duration.ofMinutes(15), this.clock);
	
	@Test
	void locksOutAfterMaxFailures() {
		for (int i = 0; i < 3; i++) {
			this.throttle.check("10.0.0.1", "admin");
			this.throttle.failure("10.0.0.1", "admin");
		}
		HttpError error = assertThrows(HttpError.class, () -> this.throttle.check("10.0.0.1", "admin"));
		assertEquals(429, error.status());
		assertEquals("900", error.headers().get("Retry-After"));
	}
	
	@Test
	void lockoutIsPerAddressAndUsername() {
		for (int i = 0; i < 3; i++) {
			this.throttle.failure("10.0.0.1", "admin");
		}
		assertDoesNotThrow(() -> this.throttle.check("10.0.0.2", "admin"));
		assertDoesNotThrow(() -> this.throttle.check("10.0.0.1", "other"));
		assertThrows(HttpError.class, () -> this.throttle.check("10.0.0.1", "ADMIN"));
	}
	
	@Test
	void addressIsLockedOutAfterFailuresForManyUsers() {
		for (int i = 0; i < 15; i++) {
			this.throttle.failure("10.0.0.1", "user" + i);
		}
		assertThrows(HttpError.class, () -> this.throttle.check("10.0.0.1", "fresh"));
		assertDoesNotThrow(() -> this.throttle.check("10.0.0.2", "fresh"));
	}
	
	@Test
	void lockoutEndsAfterLockoutPeriod() {
		for (int i = 0; i < 3; i++) {
			this.throttle.failure("10.0.0.1", "admin");
		}
		this.clock.advance(Duration.ofMinutes(15));
		assertDoesNotThrow(() -> this.throttle.check("10.0.0.1", "admin"));
		this.throttle.failure("10.0.0.1", "admin");
		assertDoesNotThrow(() -> this.throttle.check("10.0.0.1", "admin"));
	}
	
	@Test
	void successResetsFailures() {
		this.throttle.failure("10.0.0.1", "admin");
		this.throttle.failure("10.0.0.1", "admin");
		this.throttle.success("10.0.0.1", "admin");
		this.throttle.failure("10.0.0.1", "admin");
		assertDoesNotThrow(() -> this.throttle.check("10.0.0.1", "admin"));
	}
	
	private static final class MutableClock extends Clock {
		
		private Instant now = Instant.parse("2026-01-01T00:00:00Z");
		
		private void advance(Duration duration) {
			this.now = this.now.plus(duration);
		}
		
		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}
		
		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
		
		@Override
		public Instant instant() {
			return this.now;
		}
	}
}
