package net.luis.artifactory.auth;

import net.luis.artifactory.http.HttpError;
import org.jspecify.annotations.NonNull;

import java.time.*;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits failed password logins to slow down password guessing.<br>
 * <p>
 *     Failures are counted per client address and user name, and per client address alone with a five times higher limit
 *     so one address can not try many user names. Once a limit is reached further attempts are rejected with
 *     {@code 429 Too Many Requests} until the lockout period since the first counted failure has passed.
 *     A successful login resets the counter of that address and user name.
 * </p>
 */
public class LoginThrottle {

	private static final int ADDRESS_FACTOR = 5;
	private static final int MAX_ENTRIES = 100_000;

	private final int maxFailures;
	private final Duration lockout;
	private final Clock clock;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	public LoginThrottle(int maxFailures, @NonNull Duration lockout) {
		this(maxFailures, lockout, Clock.systemUTC());
	}

	LoginThrottle(int maxFailures, @NonNull Duration lockout, @NonNull Clock clock) {
		this.maxFailures = maxFailures;
		this.lockout = Objects.requireNonNull(lockout, "Lockout must not be null");
		this.clock = Objects.requireNonNull(clock, "Clock must not be null");
	}

	/**
	 * @throws HttpError 429 if the address or the address and user name are locked out
	 */
	public void check(@NonNull String address, @NonNull String username) {
		Instant now = this.clock.instant();
		this.checkWindow(userKey(address, username), this.maxFailures, now);
		this.checkWindow(addressKey(address), this.maxFailures * ADDRESS_FACTOR, now);
	}

	public void failure(@NonNull String address, @NonNull String username) {
		if (this.windows.size() >= MAX_ENTRIES) {
			this.cleanup();
		}
		Instant now = this.clock.instant();
		for (String key : new String[] { userKey(address, username), addressKey(address) }) {
			this.windows.compute(key, (_, window) -> window == null || window.expired(now, this.lockout) ? new Window(now, 1) : new Window(window.start(), window.failures() + 1));
		}
	}

	public void success(@NonNull String address, @NonNull String username) {
		this.windows.remove(userKey(address, username));
	}

	/**
	 * Removes the counters whose lockout period has passed.<br>
	 */
	public void cleanup() {
		Instant now = this.clock.instant();
		this.windows.values().removeIf(window -> window.expired(now, this.lockout));
	}

	private void checkWindow(@NonNull String key, int limit, @NonNull Instant now) {
		Window window = this.windows.get(key);
		if (window != null && !window.expired(now, this.lockout) && window.failures() >= limit) {
			long retryAfter = Math.max(1, Duration.between(now, window.start().plus(this.lockout)).toSeconds());
			throw HttpError.tooManyRequests(retryAfter);
		}
	}

	private static @NonNull String userKey(@NonNull String address, @NonNull String username) {
		return "user:" + address + "|" + username.toLowerCase(Locale.ROOT);
	}

	private static @NonNull String addressKey(@NonNull String address) {
		return "address:" + address;
	}

	private record Window(@NonNull Instant start, int failures) {

		private boolean expired(@NonNull Instant now, @NonNull Duration lockout) {
			return !now.isBefore(this.start.plus(lockout));
		}
	}
}
