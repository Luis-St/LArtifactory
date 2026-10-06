package net.luis.artifactory.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VersionsTest {

	@Test
	void mavenOrdersQualifiers() {
		List<String> versions = new ArrayList<>(List.of("1.0.1", "1.0", "1.0-SNAPSHOT", "1.0-rc1", "1.0-alpha", "1.0-beta-2", "1.0-beta-10", "0.9", "1.0-sp1"));
		versions.sort(Versions.MAVEN);
		assertEquals(List.of("0.9", "1.0-alpha", "1.0-beta-2", "1.0-beta-10", "1.0-rc1", "1.0-SNAPSHOT", "1.0", "1.0-sp1", "1.0.1"), versions);
	}

	@Test
	void mavenTreatsTrailingZerosAsEqual() {
		assertEquals(0, Versions.MAVEN.compare("1.0.0", "1"));
		assertEquals(0, Versions.MAVEN.compare("1.0-final", "1.0"));
		assertTrue(Versions.MAVEN.compare("1.10", "1.9") > 0);
	}

	@Test
	void semverOrdersPrereleases() {
		List<String> versions = new ArrayList<>(List.of("1.0.0", "1.0.0-rc.1", "1.0.0-beta.11", "1.0.0-beta.2", "1.0.0-alpha", "1.0.0-alpha.1", "0.9.0", "1.1.0", "1.0.0-beta"));
		versions.sort(Versions.SEMVER);
		assertEquals(List.of("0.9.0", "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.1.0"), versions);
	}

	@Test
	void semverIgnoresBuildMetadata() {
		assertEquals(0, Versions.SEMVER.compare("1.0.0+build.1", "1.0.0+build.2"));
		assertFalse(Versions.isPrerelease("1.0.0+build-1"));
		assertTrue(Versions.isPrerelease("1.0.0-rc.1+build"));
	}

	@Test
	void nugetNormalization() {
		assertEquals("1.0.0", Versions.normalizeNuGet("1.0"));
		assertEquals("1.0.0", Versions.normalizeNuGet("1.0.0.0"));
		assertEquals("1.0.0.1", Versions.normalizeNuGet("1.0.0.1"));
		assertEquals("1.2.3", Versions.normalizeNuGet("01.02.03"));
		assertEquals("1.0.0-Beta.1", Versions.normalizeNuGet("1.0.0-Beta.1+sha.abc"));
		assertEquals("2.0.0", Versions.normalizeNuGet("2"));
		assertThrows(IllegalArgumentException.class, () -> Versions.normalizeNuGet("1.a.0"));
		assertThrows(IllegalArgumentException.class, () -> Versions.normalizeNuGet("1.0.0.0.0"));
	}
}
