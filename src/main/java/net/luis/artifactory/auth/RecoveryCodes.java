package net.luis.artifactory.auth;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Two-factor recovery codes: {@value #COUNT} single use codes of the format {@code ABCD-EFGH} (four upper case letters, a dash, four upper case letters).<br>
 * Only the {@link Pepper keyed} hashes of the codes are stored, a code is shown once when the set is generated.<br>
 */
public final class RecoveryCodes {
	
	private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	private static final Pattern FORMAT = Pattern.compile("[A-Z]{4}-[A-Z]{4}");
	private static final String PEPPER_PURPOSE = "recovery-code";
	private static final String SEPARATOR = ",";
	private static final SecureRandom RANDOM = new SecureRandom();
	public static final int COUNT = 8;
	
	private RecoveryCodes() {}
	
	/**
	 * Generates a new set of distinct codes.<br>
	 */
	@NonNull
	public static List<String> generate() {
		Set<String> codes = new LinkedHashSet<>();
		while (codes.size() < COUNT) {
			StringBuilder code = new StringBuilder(9);
			for (int i = 0; i < 8; i++) {
				if (i == 4) {
					code.append('-');
				}
				code.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
			}
			codes.add(code.toString());
		}
		return List.copyOf(codes);
	}
	
	/**
	 * Brings user input into the code format: upper case, without spaces, with the dash added if it was left out.<br>
	 *
	 * @return The normalized code, null if the input can not be a recovery code
	 */
	@Nullable
	public static String normalize(@Nullable String input) {
		if (input == null) {
			return null;
		}
		String value = input.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
		if (value.length() == 8 && value.indexOf('-') < 0) {
			value = value.substring(0, 4) + "-" + value.substring(4);
		}
		return FORMAT.matcher(value).matches() ? value : null;
	}
	
	public static boolean looksLikeCode(@Nullable String input) {
		return normalize(input) != null;
	}
	
	/**
	 * Returns the stored form of a set of codes, the joined hashes.<br>
	 */
	@NonNull
	public static String hashAll(@NonNull Pepper pepper, @NonNull List<String> codes) {
		return String.join(SEPARATOR, codes.stream().map(code -> pepper.hmacHex(PEPPER_PURPOSE, code)).toList());
	}
	
	public static int remaining(@Nullable String stored) {
		return stored == null || stored.isBlank() ? 0 : stored.split(SEPARATOR).length;
	}
	
	/**
	 * Removes the code from the stored set.<br>
	 *
	 * @return The stored set without the code, null if the code is not part of it
	 */
	@Nullable
	public static String consume(@NonNull Pepper pepper, @Nullable String stored, @Nullable String input) {
		String code = normalize(input);
		if (code == null || stored == null || stored.isBlank()) {
			return null;
		}
		String hash = pepper.hmacHex(PEPPER_PURPOSE, code);
		List<String> hashes = new ArrayList<>(List.of(stored.split(SEPARATOR)));
		if (!hashes.remove(hash)) {
			return null;
		}
		return String.join(SEPARATOR, hashes);
	}
}
