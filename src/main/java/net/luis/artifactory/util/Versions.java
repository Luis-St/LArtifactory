package net.luis.artifactory.util;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version comparators of the supported package ecosystems.<br>
 */
public final class Versions {
	
	private static final List<String> MAVEN_QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");
	private static final Pattern PEP440_VERSION = Pattern.compile(
		"v?(?:(\\d+)!)?(\\d+(?:\\.\\d+)*)" +
			"(?:[-_.]?(a|alpha|b|beta|c|rc|pre|preview)[-_.]?(\\d*))?" +
			"(?:-(\\d+)|[-_.]?(?:post|rev|r)[-_.]?(\\d*))?" +
			"(?:[-_.]?dev[-_.]?(\\d*))?" +
			"(?:\\+[a-z0-9]+(?:[-_.][a-z0-9]+)*)?",
		Pattern.CASE_INSENSITIVE
	);
	/**
	 * Compares versions like Maven's ComparableVersion (simplified).<br>
	 */
	public static final Comparator<String> MAVEN = Versions::compareMaven;
	/**
	 * Compares semantic versions (npm, Cargo, NuGet), versions with more than three numeric parts are supported.<br>
	 */
	public static final Comparator<String> SEMVER = Versions::compareSemver;
	/**
	 * Compares Python versions as defined by PEP 440 (local versions are ignored).<br>
	 * Versions that do not follow PEP 440 are older than all valid versions and are compared as text.<br>
	 */
	public static final Comparator<String> PEP440 = Versions::comparePep440;
	
	private Versions() {}
	
	private static int compareMaven(@NonNull String first, @NonNull String second) {
		List<Object> a = tokenizeMaven(first);
		List<Object> b = tokenizeMaven(second);
		int length = Math.max(a.size(), b.size());
		for (int i = 0; i < length; i++) {
			Object left = i < a.size() ? a.get(i) : null;
			Object right = i < b.size() ? b.get(i) : null;
			int result = compareMavenItem(left, right);
			if (result != 0) {
				return result;
			}
		}
		return 0;
	}
	
	private static int compareMavenItem(Object left, Object right) {
		if (left == null && right == null) {
			return 0;
		}
		if (left == null) {
			return -compareMavenItem(right, null);
		}
		if (left instanceof BigInteger number) {
			if (right == null) {
				return number.signum() == 0 ? 0 : 1;
			}
			if (right instanceof BigInteger other) {
				return number.compareTo(other);
			}
			return 1; // numbers are newer than qualifiers
		}
		String qualifier = (String) left;
		if (right == null) {
			return compareQualifier(qualifier, "");
		}
		if (right instanceof BigInteger) {
			return -1;
		}
		return compareQualifier(qualifier, (String) right);
	}
	
	private static int compareQualifier(@NonNull String first, @NonNull String second) {
		return qualifierKey(first).compareTo(qualifierKey(second));
	}
	
	private static @NonNull String qualifierKey(@NonNull String qualifier) {
		String normalized = switch (qualifier) {
			case "a" -> "alpha";
			case "b" -> "beta";
			case "m" -> "milestone";
			case "cr" -> "rc";
			case "ga", "final", "release" -> "";
			default -> qualifier;
		};
		int index = MAVEN_QUALIFIERS.indexOf(normalized);
		return index >= 0 ? String.valueOf(index) : MAVEN_QUALIFIERS.size() + "-" + normalized;
	}
	
	private static @NonNull List<Object> tokenizeMaven(@NonNull String version) {
		List<Object> tokens = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		Boolean digit = null;
		for (char c : version.toLowerCase(Locale.ROOT).toCharArray()) {
			if (c == '.' || c == '-' || c == '_' || c == '+') {
				addMavenToken(tokens, current);
				digit = null;
				continue;
			}
			boolean isDigit = Character.isDigit(c);
			if (digit != null && digit != isDigit) {
				addMavenToken(tokens, current);
			}
			digit = isDigit;
			current.append(c);
		}
		addMavenToken(tokens, current);
		
		while (!tokens.isEmpty()) { // trailing zeros and release qualifiers are irrelevant
			Object last = tokens.getLast();
			if ((last instanceof BigInteger number && number.signum() == 0) || (last instanceof String text && qualifierKey(text).equals(qualifierKey("")))) {
				tokens.removeLast();
			} else {
				break;
			}
		}
		return tokens;
	}
	
	private static void addMavenToken(@NonNull List<Object> tokens, @NonNull StringBuilder current) {
		if (current.isEmpty()) {
			return;
		}
		String token = current.toString();
		current.setLength(0);
		if (token.chars().allMatch(Character::isDigit)) {
			tokens.add(new BigInteger(token));
		} else {
			tokens.add(token);
		}
	}
	
	private static int compareSemver(@NonNull String first, @NonNull String second) {
		String a = stripBuild(first);
		String b = stripBuild(second);
		int dashA = a.indexOf('-');
		int dashB = b.indexOf('-');
		String coreA = dashA < 0 ? a : a.substring(0, dashA);
		String coreB = dashB < 0 ? b : b.substring(0, dashB);
		
		String[] partsA = coreA.split("\\.");
		String[] partsB = coreB.split("\\.");
		for (int i = 0; i < Math.max(partsA.length, partsB.length); i++) {
			BigInteger left = i < partsA.length ? parseNumber(partsA[i]) : BigInteger.ZERO;
			BigInteger right = i < partsB.length ? parseNumber(partsB[i]) : BigInteger.ZERO;
			int result = left.compareTo(right);
			if (result != 0) {
				return result;
			}
		}
		
		if (dashA < 0 && dashB < 0) {
			return 0;
		}
		if (dashA < 0) {
			return 1;
		}
		if (dashB < 0) {
			return -1;
		}
		
		String[] preA = a.substring(dashA + 1).split("\\.");
		String[] preB = b.substring(dashB + 1).split("\\.");
		for (int i = 0; i < Math.min(preA.length, preB.length); i++) {
			boolean numericA = isNumber(preA[i]);
			boolean numericB = isNumber(preB[i]);
			int result;
			if (numericA && numericB) {
				result = new BigInteger(preA[i]).compareTo(new BigInteger(preB[i]));
			} else if (numericA) {
				result = -1;
			} else if (numericB) {
				result = 1;
			} else {
				result = preA[i].compareToIgnoreCase(preB[i]);
			}
			if (result != 0) {
				return result;
			}
		}
		return Integer.compare(preA.length, preB.length);
	}
	
	private static int comparePep440(@NonNull String first, @NonNull String second) {
		Pep440Version a = Pep440Version.parse(first);
		Pep440Version b = Pep440Version.parse(second);
		if (a == null || b == null) {
			return a == null && b == null ? first.compareTo(second) : (a == null ? -1 : 1);
		}
		int result = a.epoch().compareTo(b.epoch());
		for (int i = 0; result == 0 && i < Math.max(a.release().size(), b.release().size()); i++) {
			BigInteger left = i < a.release().size() ? a.release().get(i) : BigInteger.ZERO;
			BigInteger right = i < b.release().size() ? b.release().get(i) : BigInteger.ZERO;
			result = left.compareTo(right);
		}
		if (result == 0) {
			result = Integer.compare(a.preRank(), b.preRank());
		}
		if (result == 0) {
			result = a.preNumber().compareTo(b.preNumber());
		}
		if (result == 0) {
			result = compareOptional(a.post(), b.post(), -1);
		}
		if (result == 0) {
			result = compareOptional(a.dev(), b.dev(), 1);
		}
		return result;
	}
	
	/**
	 * Compares two optional numbers, a missing number is lower ({@code missing = -1}) or higher ({@code missing = 1}) than every number.<br>
	 */
	private static int compareOptional(@Nullable BigInteger first, @Nullable BigInteger second, int missing) {
		if (first == null || second == null) {
			return first == second ? 0 : (first == null ? missing : -missing);
		}
		return first.compareTo(second);
	}
	
	public static boolean isPrerelease(@NonNull String version) {
		return stripBuild(version).contains("-");
	}
	
	private static @NonNull String stripBuild(@NonNull String version) {
		int plus = version.indexOf('+');
		return plus < 0 ? version : version.substring(0, plus);
	}
	
	private static boolean isNumber(@NonNull String value) {
		return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
	}
	
	private static @NonNull BigInteger parseNumber(@NonNull String value) {
		return isNumber(value) ? new BigInteger(value) : BigInteger.ZERO;
	}
	
	/**
	 * Normalizes a NuGet version as described in the NuGet documentation:<br>
	 * leading zeros are removed, a zero fourth part is omitted, missing parts are added and build metadata is dropped.<br>
	 *
	 * @throws IllegalArgumentException If the version is not a valid NuGet version
	 */
	public static @NonNull String normalizeNuGet(@NonNull String version) {
		String value = stripBuild(version.strip());
		int dash = value.indexOf('-');
		String core = dash < 0 ? value : value.substring(0, dash);
		String prerelease = dash < 0 ? null : value.substring(dash + 1);
		if (prerelease != null && !prerelease.chars().allMatch(c -> c == '.' || c == '-' || (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
			throw new IllegalArgumentException("Invalid NuGet version: " + version);
		}
		
		String[] parts = core.split("\\.");
		if (parts.length < 1 || parts.length > 4) {
			throw new IllegalArgumentException("Invalid NuGet version: " + version);
		}
		List<String> numbers = new ArrayList<>();
		for (String part : parts) {
			if (!isNumber(part)) {
				throw new IllegalArgumentException("Invalid NuGet version: " + version);
			}
			numbers.add(new BigInteger(part).toString());
		}
		while (numbers.size() < 3) {
			numbers.add("0");
		}
		if (numbers.size() == 4 && "0".equals(numbers.get(3))) {
			numbers.removeLast();
		}
		return String.join(".", numbers) + (prerelease == null || prerelease.isEmpty() ? "" : "-" + prerelease);
	}
	
	/**
	 * A parsed PEP 440 version.<br>
	 *
	 * @param preRank The rank of the pre-release phase (alpha 0, beta 1, rc 2), -1 for a dev release without pre- and post-release
	 *                (older than all pre-releases) and 3 without pre-release
	 * @param post The post-release number, null without post-release
	 * @param dev The dev-release number, null without dev-release
	 */
	private record Pep440Version(@NonNull BigInteger epoch, @NonNull List<BigInteger> release, int preRank, @NonNull BigInteger preNumber, @Nullable BigInteger post, @Nullable BigInteger dev) {
		
		private static @Nullable Pep440Version parse(@NonNull String version) {
			Matcher matcher = PEP440_VERSION.matcher(version.strip());
			if (!matcher.matches()) {
				return null;
			}
			List<BigInteger> release = new ArrayList<>();
			for (String part : matcher.group(2).split("\\.")) {
				release.add(new BigInteger(part));
			}
			String phase = matcher.group(3);
			BigInteger post = matcher.group(5) != null ? new BigInteger(matcher.group(5)) : optionalNumber(matcher, 6);
			BigInteger dev = optionalNumber(matcher, 7);
			int preRank;
			if (phase == null) {
				preRank = post == null && dev != null ? -1 : 3;
			} else {
				preRank = switch (phase.toLowerCase(Locale.ROOT)) {
					case "a", "alpha" -> 0;
					case "b", "beta" -> 1;
					default -> 2;
				};
			}
			BigInteger preNumber = phase == null || matcher.group(4).isEmpty() ? BigInteger.ZERO : new BigInteger(matcher.group(4));
			return new Pep440Version(matcher.group(1) == null ? BigInteger.ZERO : new BigInteger(matcher.group(1)), release, preRank, preNumber, post, dev);
		}
		
		/**
		 * Returns the number of an optional segment, an implicit number is 0 and null if the segment is missing.<br>
		 */
		private static @Nullable BigInteger optionalNumber(@NonNull Matcher matcher, int group) {
			if (matcher.start(group) < 0) {
				return null;
			}
			String number = matcher.group(group);
			return number.isEmpty() ? BigInteger.ZERO : new BigInteger(number);
		}
	}
}
