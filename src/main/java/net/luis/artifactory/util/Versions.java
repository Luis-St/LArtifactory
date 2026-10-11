package net.luis.artifactory.util;

import org.jspecify.annotations.NonNull;

import java.math.BigInteger;
import java.util.*;

/**
 * Version comparators of the supported package ecosystems.<br>
 */
public final class Versions {
	
	private static final List<String> MAVEN_QUALIFIERS = List.of("alpha", "beta", "milestone", "rc", "snapshot", "", "sp");
	
	/**
	 * Compares versions like Maven's ComparableVersion (simplified).<br>
	 */
	public static final Comparator<String> MAVEN = Versions::compareMaven;
	
	/**
	 * Compares semantic versions (npm, Cargo, NuGet), versions with more than three numeric parts are supported.<br>
	 */
	public static final Comparator<String> SEMVER = Versions::compareSemver;
	
	private Versions() {}
	
	//region Maven
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
	//endregion
	
	//region SemVer
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
	//endregion
	
	//region NuGet
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
	//endregion
}
