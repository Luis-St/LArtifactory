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
	void mavenRemovesZerosBeforeEveryHyphen() {
		assertTrue(Versions.MAVEN.compare("1.0-beta", "1.0.0-alpha") > 0);
		assertEquals(0, Versions.MAVEN.compare("1.0.0-beta", "1.0-beta"));
		assertTrue(Versions.MAVEN.compare("1.0-1", "1.0.1") < 0, "a dash starts a sublist that is older than a number");
		assertTrue(Versions.MAVEN.compare("1-SNAPSHOT", "1.0.1") < 0);
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
	void pep440OrdersReleaseSegments() {
		List<String> versions = new ArrayList<>(List.of("1.0", "1.0.post1", "1.0rc1", "1.0.dev1", "1.0a1", "1.0b2.dev3", "1.0b2", "1.0a1.dev1", "2!0.1", "0.9", "1.0.post1.dev1", "1.1"));
		versions.sort(Versions.PEP440);
		assertEquals(List.of("0.9", "1.0.dev1", "1.0a1.dev1", "1.0a1", "1.0b2.dev3", "1.0b2", "1.0rc1", "1.0", "1.0.post1.dev1", "1.0.post1", "1.1", "2!0.1"), versions);
	}
	
	@Test
	void pep440NormalizesSpellings() {
		assertEquals(0, Versions.PEP440.compare("1.0", "1.0.0"));
		assertEquals(0, Versions.PEP440.compare("1.0-alpha-1", "1.0a1"));
		assertEquals(0, Versions.PEP440.compare("1.0-1", "1.0.post1"));
		assertEquals(0, Versions.PEP440.compare("1.0+local", "1.0"));
		assertTrue(Versions.PEP440.compare("1.10", "1.9") > 0);
		assertTrue(Versions.PEP440.compare("not a version", "0.1") < 0, "invalid versions are older than all valid versions");
	}
	
	@Test
	void pep440NormalizedForm() {
		assertEquals("1.0.0b1", Versions.normalizePep440("1.0.0-beta1"));
		assertEquals("1.0.0a2", Versions.normalizePep440("1.0.0-alpha.2"));
		assertEquals("1.0.post1", Versions.normalizePep440("1.0-1"));
		assertEquals("1.0.dev0", Versions.normalizePep440("1.0.dev"));
		assertEquals("1!2.0rc1", Versions.normalizePep440("1!2.0RC1"));
		assertEquals("1.0+ubuntu.1", Versions.normalizePep440("1.0+Ubuntu-1"));
		assertEquals("1.0", Versions.normalizePep440("v1.0"));
		assertNull(Versions.normalizePep440("not a version"));
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
