package net.luis.artifactory.handler.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormatHandlerTest {
	
	@Test
	void cargoIndexPath() {
		assertEquals("1/a", CargoHandler.indexPath("a"));
		assertEquals("2/ab", CargoHandler.indexPath("ab"));
		assertEquals("3/a/abc", CargoHandler.indexPath("abc"));
		assertEquals("ca/rg/cargo", CargoHandler.indexPath("cargo"));
		assertEquals("lu/is/luisst-toolkit", CargoHandler.indexPath("Luisst-Toolkit"));
	}
	
	@Test
	void pypiNormalization() {
		assertEquals("luis-toolkit", PypiHandler.normalize("Luis_Toolkit"));
		assertEquals("luis-toolkit", PypiHandler.normalize("luis.-_toolkit"));
		assertEquals("lartifactory-test-pkg", PypiHandler.normalize("LArtifactory_Test.Pkg"));
	}
	
	@Test
	void pypiFileNamesMatchProject() {
		assertTrue(PypiHandler.matchesProject("lartifactory_test_pkg-1.0.0-py3-none-any.whl", "LArtifactory_Test.Pkg", "1.0.0"));
		assertTrue(PypiHandler.matchesProject("lartifactory_test_pkg-1.0.0.tar.gz", "LArtifactory_Test.Pkg", "1.0.0"));
		assertTrue(PypiHandler.matchesProject("Foo-Bar-2.0rc1.zip", "foo-bar", "2.0RC1"));
		assertFalse(PypiHandler.matchesProject("bar-1.0.0-py3-none-any.whl", "foo", "1.0.0"), "file of another project");
		assertFalse(PypiHandler.matchesProject("foo-bar-1.0.0.tar.gz", "foo", "1.0.0"), "file of a project with the name as prefix");
		assertFalse(PypiHandler.matchesProject("foo-1.0.1.tar.gz", "foo", "1.0"), "file of another version");
		assertFalse(PypiHandler.matchesProject("foo-1.0.1-py3-none-any.whl", "foo", "1.0"), "wheel of another version");
		assertFalse(PypiHandler.matchesProject("foo-1.0.exe", "foo", "1.0"), "unknown distribution type");
	}
	
	@Test
	void mavenReleaseCoordinates() {
		MavenHandler.Coordinates coordinates = MavenHandler.parseCoordinates("net/luisst/toolkit/1.0.7/toolkit-1.0.7-sources.jar");
		assertNotNull(coordinates);
		assertEquals("net.luisst", coordinates.groupId());
		assertEquals("toolkit", coordinates.artifactId());
		assertEquals("1.0.7", coordinates.version());
		assertEquals("net.luisst:toolkit", coordinates.packageName());
		assertFalse(coordinates.isSnapshot());
	}
	
	@Test
	void mavenSnapshotCoordinates() {
		MavenHandler.Coordinates coordinates = MavenHandler.parseCoordinates("net/luisst/toolkit/1.1.0-SNAPSHOT/toolkit-1.1.0-20260120.143000-7.jar");
		assertNotNull(coordinates);
		assertEquals("1.1.0-SNAPSHOT", coordinates.version());
		assertTrue(coordinates.isSnapshot());
	}
	
	@Test
	void mavenInvalidCoordinates() {
		assertNull(MavenHandler.parseCoordinates("net/luisst/toolkit/maven-metadata.xml"));
		assertNull(MavenHandler.parseCoordinates("toolkit/1.0.7/toolkit-1.0.7.jar"));
		assertNull(MavenHandler.parseCoordinates("net/luisst/toolkit/1.0.7/other-1.0.7.jar"));
	}
	
	@Test
	void contentTypes() {
		assertEquals("application/java-archive", PathRepositoryHandler.contentType("a/b/c-1.0.jar"));
		assertEquals("application/xml", PathRepositoryHandler.contentType("a/b/c-1.0.pom"));
		assertEquals("application/json", PathRepositoryHandler.contentType("a/b/c-1.0.module"));
		assertEquals("text/plain", PathRepositoryHandler.contentType("a/b/c-1.0.jar.sha1"));
		assertEquals("application/octet-stream", PathRepositoryHandler.contentType("a/b/c.bin"));
	}
}
