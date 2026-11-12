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
			"(?:\\+([a-z0-9]+(?:[-_.][a-z0-9]+)*))?",
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
		return compareMavenItem(parseMaven(first), parseMaven(second));
	}
	
	/**
	 * Parses a version into the item tree of Maven's {@code ComparableVersion}: a dash and a change between digits and letters
	 * start a nested list, each list is normalized by removing its trailing null items (zero, release qualifiers, empty lists).<br>
	 */
	@NonNull
	private static List<Object> parseMaven(@NonNull String version) {
		String value = version.toLowerCase(Locale.ROOT);
		List<Object> root = new ArrayList<>();
		List<Object> list = root;
		Deque<List<Object>> stack = new ArrayDeque<>();
		stack.push(root);
		boolean digit = false;
		int start = 0;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '.' || c == '-') {
				list.add(i == start ? BigInteger.ZERO : mavenItem(value.substring(start, i), digit, false));
				start = i + 1;
				if (c == '-') {
					list = nestedList(list, stack);
				}
			} else if (Character.isDigit(c)) {
				if (!digit && i > start) {
					list.add(mavenItem(value.substring(start, i), false, true));
					start = i;
					list = nestedList(list, stack);
				}
				digit = true;
			} else {
				if (digit && i > start) {
					list.add(mavenItem(value.substring(start, i), true, false));
					start = i;
					list = nestedList(list, stack);
				}
				digit = false;
			}
		}
		if (value.length() > start) {
			list.add(mavenItem(value.substring(start), digit, false));
		}
		while (!stack.isEmpty()) {
			normalizeMaven(stack.pop());
		}
		return root;
	}
	
	@NonNull
	private static List<Object> nestedList(@NonNull List<Object> parent, @NonNull Deque<List<Object>> stack) {
		List<Object> nested = new ArrayList<>();
		parent.add(nested);
		stack.push(nested);
		return nested;
	}
	
	@NonNull
	private static Object mavenItem(@NonNull String token, boolean digit, boolean followedByDigit) {
		if (digit) {
			return new BigInteger(token);
		}
		if (followedByDigit && token.length() == 1) {
			// a1, b2 and m3 are short forms, a single letter without number is a plain qualifier
			return switch (token) {
				case "a" -> "alpha";
				case "b" -> "beta";
				case "m" -> "milestone";
				default -> token;
			};
		}
		return token;
	}
	
	private static void normalizeMaven(@NonNull List<Object> list) {
		for (int i = list.size() - 1; i >= 0; i--) {
			Object item = list.get(i);
			if (isNullMavenItem(item)) {
				list.remove(i);
			} else if (!(item instanceof List<?>)) {
				break;
			}
		}
	}
	
	private static boolean isNullMavenItem(@NonNull Object item) {
		return switch (item) {
			case BigInteger number -> number.signum() == 0;
			case String qualifier -> qualifierKey(qualifier).equals(qualifierKey(""));
			case List<?> list -> list.isEmpty();
			default -> false;
		};
	}
	
	@SuppressWarnings("unchecked")
	private static int compareMavenItem(@Nullable Object left, @Nullable Object right) {
		if (left == null) {
			return right == null ? 0 : -compareMavenItem(right, null);
		}
		return switch (left) {
			case BigInteger number -> switch (right) {
				case null -> number.signum() == 0 ? 0 : 1;
				case BigInteger other -> number.compareTo(other);
				default -> 1; // numbers are newer than qualifiers and lists
			};
			case String qualifier -> switch (right) {
				case null -> compareQualifier(qualifier, "");
				case String other -> compareQualifier(qualifier, other);
				default -> -1;
			};
			default -> {
				List<Object> list = (List<Object>) left;
				yield switch (right) {
					case null -> list.isEmpty() ? 0 : compareMavenItem(list.getFirst(), null);
					case BigInteger ignored -> -1;
					case String ignored -> 1;
					default -> {
						List<Object> other = (List<Object>) right;
						for (int i = 0; i < Math.max(list.size(), other.size()); i++) {
							int result = compareMavenItem(i < list.size() ? list.get(i) : null, i < other.size() ? other.get(i) : null);
							if (result != 0) {
								yield result;
							}
						}
						yield 0;
					}
				};
			}
		};
	}
	
	private static int compareQualifier(@NonNull String first, @NonNull String second) {
		return qualifierKey(first).compareTo(qualifierKey(second));
	}
	
	@NonNull
	private static String qualifierKey(@NonNull String qualifier) {
		String normalized = switch (qualifier) {
			case "cr" -> "rc";
			case "ga", "final", "release" -> "";
			default -> qualifier;
		};
		int index = MAVEN_QUALIFIERS.indexOf(normalized);
		return index >= 0 ? String.valueOf(index) : MAVEN_QUALIFIERS.size() + "-" + normalized;
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
	
	/**
	 * Returns the normalized form of a PEP 440 version, e.g. {@code 1.0.0b1} for {@code 1.0.0-beta1} and {@code 1.0.post1} for {@code 1.0-1}.<br>
	 * Trailing zeros of the release segment are kept, {@code 1.0} and {@code 1.0.0} are equal according to {@link #PEP440} but stay distinct strings.<br>
	 *
	 * @return The normalized version, null if the version does not follow PEP 440
	 */
	@Nullable
	public static String normalizePep440(@NonNull String version) {
		Pep440Version parsed = Pep440Version.parse(version);
		if (parsed == null) {
			return null;
		}
		StringBuilder builder = new StringBuilder();
		if (parsed.epoch().signum() != 0) {
			builder.append(parsed.epoch()).append('!');
		}
		builder.append(String.join(".", parsed.release().stream().map(BigInteger::toString).toList()));
		if (parsed.preRank() >= 0 && parsed.preRank() <= 2) {
			builder.append(List.of("a", "b", "rc").get(parsed.preRank())).append(parsed.preNumber());
		}
		if (parsed.post() != null) {
			builder.append(".post").append(parsed.post());
		}
		if (parsed.dev() != null) {
			builder.append(".dev").append(parsed.dev());
		}
		if (parsed.local() != null) {
			builder.append('+').append(parsed.local().toLowerCase(Locale.ROOT).replace('-', '.').replace('_', '.'));
		}
		return builder.toString();
	}
	
	public static boolean isPrerelease(@NonNull String version) {
		return stripBuild(version).contains("-");
	}
	
	@NonNull
	private static String stripBuild(@NonNull String version) {
		int plus = version.indexOf('+');
		return plus < 0 ? version : version.substring(0, plus);
	}
	
	private static boolean isNumber(@NonNull String value) {
		return !value.isEmpty() && value.chars().allMatch(Character::isDigit);
	}
	
	@NonNull
	private static BigInteger parseNumber(@NonNull String value) {
		return isNumber(value) ? new BigInteger(value) : BigInteger.ZERO;
	}
	
	/**
	 * Normalizes a NuGet version as described in the NuGet documentation:<br>
	 * leading zeros are removed, a zero fourth part is omitted, missing parts are added and build metadata is dropped.<br>
	 *
	 * @throws IllegalArgumentException If the version is not a valid NuGet version
	 */
	@NonNull
	public static String normalizeNuGet(@NonNull String version) {
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
	 * @param local The local version label as written, null without local version
	 */
	private record Pep440Version(@NonNull BigInteger epoch, @NonNull List<BigInteger> release, int preRank, @NonNull BigInteger preNumber, @Nullable BigInteger post, @Nullable BigInteger dev, @Nullable String local) {
		
		@Nullable
		private static Pep440Version parse(@NonNull String version) {
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
			return new Pep440Version(matcher.group(1) == null ? BigInteger.ZERO : new BigInteger(matcher.group(1)), release, preRank, preNumber, post, dev, matcher.group(8));
		}
		
		/**
		 * Returns the number of an optional segment, an implicit number is 0 and null if the segment is missing.<br>
		 */
		@Nullable
		private static BigInteger optionalNumber(@NonNull Matcher matcher, int group) {
			if (matcher.start(group) < 0) {
				return null;
			}
			String number = matcher.group(group);
			return number.isEmpty() ? BigInteger.ZERO : new BigInteger(number);
		}
	}
}
