package net.luis.artifactory.storage;

import net.luis.artifactory.http.HttpError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BlobStoreTest {
	
	@TempDir
	Path root;
	
	@Test
	void storesContentAddressedWithDigests() throws IOException {
		BlobStore store = new BlobStore(this.root, 1024, Duration.ZERO);
		StoredBlob blob = store.store("hello".getBytes(StandardCharsets.UTF_8));
		assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", blob.sha256());
		assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", blob.sha1());
		assertEquals(5, blob.size());
		assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), store.read(blob.sha256()));
		assertTrue(Files.exists(this.root.resolve("blobs/2c/f2").resolve(blob.sha256())));
	}
	
	@Test
	void rejectsContentLargerThanMaxSize() {
		BlobStore store = new BlobStore(this.root, 4, Duration.ZERO);
		HttpError error = assertThrows(HttpError.class, () -> store.store("hello".getBytes(StandardCharsets.UTF_8)));
		assertEquals(413, error.status());
		assertEquals(0, store.cleanTemporaryFiles(Duration.ZERO), "the temporary upload file must be removed");
	}
	
	@Test
	void keepsReferencedBlobs() throws IOException {
		BlobStore store = new BlobStore(this.root, 1024, Duration.ZERO);
		StoredBlob blob = store.store("hello".getBytes(StandardCharsets.UTF_8));
		assertFalse(store.deleteIfUnreferenced(blob.sha256(), _ -> true));
		assertTrue(store.exists(blob.sha256()));
	}
	
	@Test
	void keepsUnreferencedBlobsWithinGracePeriod() throws IOException {
		BlobStore store = new BlobStore(this.root, 1024, Duration.ofHours(1));
		StoredBlob blob = store.store("hello".getBytes(StandardCharsets.UTF_8));
		assertFalse(store.deleteIfUnreferenced(blob.sha256(), _ -> false));
		assertEquals(0, store.sweepOrphans(_ -> false));
		assertTrue(store.exists(blob.sha256()));
	}
	
	@Test
	void storingExistingContentRestartsGracePeriod() throws IOException {
		BlobStore store = new BlobStore(this.root, 1024, Duration.ofHours(1));
		StoredBlob blob = store.store("hello".getBytes(StandardCharsets.UTF_8));
		Path path = store.path(blob.sha256());
		Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
		store.store("hello".getBytes(StandardCharsets.UTF_8));
		assertFalse(store.deleteIfUnreferenced(blob.sha256(), _ -> false), "a concurrent upload of the same content must keep the blob");
	}
	
	@Test
	void sweepDeletesOldUnreferencedBlobs() throws IOException {
		BlobStore store = new BlobStore(this.root, 1024, Duration.ofHours(1));
		StoredBlob orphan = store.store("orphan".getBytes(StandardCharsets.UTF_8));
		StoredBlob referenced = store.store("referenced".getBytes(StandardCharsets.UTF_8));
		for (StoredBlob blob : new StoredBlob[] { orphan, referenced }) {
			Files.setLastModifiedTime(store.path(blob.sha256()), FileTime.from(Instant.now().minus(Duration.ofHours(2))));
		}
		assertEquals(1, store.sweepOrphans(sha256 -> sha256.equals(referenced.sha256())));
		assertFalse(store.exists(orphan.sha256()));
		assertTrue(store.exists(referenced.sha256()));
	}
	
	@Test
	void cleansOnlyOldTemporaryFiles() throws IOException {
		BlobStore store = new BlobStore(this.root, 1024, Duration.ZERO);
		Path old = Files.writeString(this.root.resolve("tmp/old.tmp"), "old");
		Path recent = Files.writeString(this.root.resolve("tmp/recent.tmp"), "recent");
		Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
		assertEquals(1, store.cleanTemporaryFiles(Duration.ofDays(1)));
		assertFalse(Files.exists(old));
		assertTrue(Files.exists(recent));
	}
	
	@Test
	void rejectsInvalidDigests() {
		BlobStore store = new BlobStore(this.root, 1024, Duration.ZERO);
		assertThrows(IllegalArgumentException.class, () -> store.path("../../etc/passwd"));
		assertThrows(IllegalArgumentException.class, () -> store.path("A".repeat(64)));
	}
}
