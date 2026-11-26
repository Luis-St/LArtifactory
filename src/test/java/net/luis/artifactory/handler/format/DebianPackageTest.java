package net.luis.artifactory.handler.format;

import net.luis.artifactory.handler.format.DebianPackage.Control;
import net.luis.artifactory.handler.format.DebianPackage.InvalidPackageException;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DebianPackageTest {
	
	private static final String CONTROL = "Package: lat-test\nVersion: 1:1.0-1\nArchitecture: amd64\nMaintainer: Tests <tests@example.com>\nDescription: Test package\n A longer description.\n .\n Second paragraph.\n";
	
	private static byte[] tarEntry(String name, byte[] content) {
		byte[] header = new byte[512];
		byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
		byte[] size = String.format("%011o", content.length).getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(size, 0, header, 124, size.length);
		header[156] = '0';
		System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 5);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.writeBytes(header);
		output.writeBytes(content);
		output.writeBytes(new byte[(512 - content.length % 512) % 512]);
		return output.toByteArray();
	}
	
	private static byte[] arMember(String name, byte[] content) {
		String header = String.format("%-16s%-12s%-6s%-6s%-8s%-10s`\n", name, "0", "0", "0", "100644", content.length);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
		output.writeBytes(content);
		if (content.length % 2 == 1) {
			output.write('\n');
		}
		return output.toByteArray();
	}
	
	private static byte[] controlTar(String control) {
		ByteArrayOutputStream tar = new ByteArrayOutputStream();
		tar.writeBytes(tarEntry("./", new byte[0]));
		tar.writeBytes(tarEntry("./md5sums", "d41d8cd98f00b204e9800998ecf8427e  usr/share/x\n".getBytes(StandardCharsets.US_ASCII)));
		tar.writeBytes(tarEntry("./control", control.getBytes(StandardCharsets.UTF_8)));
		tar.writeBytes(new byte[1024]);
		return tar.toByteArray();
	}
	
	private static byte[] gzip(byte[] data) throws IOException {
		ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
		try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
			gzip.write(data);
		}
		return gzipped.toByteArray();
	}
	
	/**
	 * Builds an ar archive of the given members, each given as name followed by content.<br>
	 */
	private static byte[] ar(Object... members) {
		ByteArrayOutputStream deb = new ByteArrayOutputStream();
		deb.writeBytes("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
		for (int i = 0; i < members.length; i += 2) {
			deb.writeBytes(arMember((String) members[i], (byte[]) members[i + 1]));
		}
		return deb.toByteArray();
	}
	
	private static byte[] deb(String control) throws IOException {
		return ar("debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII), "control.tar.gz", gzip(controlTar(control)), "data.tar.gz", new byte[] { 1, 2, 3 });
	}
	
	private static void assertInvalid(byte[] deb) {
		assertThrows(InvalidPackageException.class, () -> DebianPackage.readControl(new ByteArrayInputStream(deb)));
	}
	
	@Test
	void readControl() throws IOException {
		Control control = DebianPackage.readControl(new ByteArrayInputStream(deb(CONTROL)));
		assertEquals("lat-test", control.get("Package"));
		assertEquals("1:1.0-1", control.get("version"), "field names are compared without regard to case");
		assertEquals("amd64", control.get("Architecture"));
		assertEquals("Test package\n A longer description.\n .\n Second paragraph.", control.get("Description"));
		assertEquals(CONTROL, control.text());
	}
	
	@Test
	void readControlRejectsInvalidPackages() throws IOException {
		assertThrows(InvalidPackageException.class, () -> DebianPackage.readControl(new ByteArrayInputStream("not a package".getBytes(StandardCharsets.US_ASCII))));
		byte[] valid = deb(CONTROL);
		byte[] truncated = java.util.Arrays.copyOf(valid, 100);
		assertThrows(IOException.class, () -> DebianPackage.readControl(new ByteArrayInputStream(truncated)));
		ByteArrayOutputStream noControl = new ByteArrayOutputStream();
		noControl.writeBytes("!<arch>\n".getBytes(StandardCharsets.US_ASCII));
		noControl.writeBytes(arMember("debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII)));
		assertThrows(InvalidPackageException.class, () -> DebianPackage.readControl(new ByteArrayInputStream(noControl.toByteArray())));
	}
	
	@Test
	void readControlRejectsWhatDpkgRejects() throws IOException {
		byte[] version = "2.0\n".getBytes(StandardCharsets.US_ASCII);
		byte[] control = gzip(controlTar(CONTROL));
		assertInvalid(ar("debian-binary", version, "data.tar.gz", new byte[] { 1 }, "control.tar.gz", control));
		assertInvalid(ar("control.tar.gz", control, "debian-binary", version));
		assertInvalid(deb(CONTROL + "Package: other\n"));
		assertDoesNotThrow(() -> DebianPackage.readControl(new ByteArrayInputStream(ar("debian-binary", version, "_extra", new byte[] { 1 }, "control.tar.gz", control))));
	}
	
	@Test
	void readControlRejectsBrokenArchivesAsInvalid() throws IOException {
		byte[] version = "2.0\n".getBytes(StandardCharsets.US_ASCII);
		assertInvalid(ar("debian-binary", version, "control.tar.gz", "not gzip at all".getBytes(StandardCharsets.US_ASCII)));
		byte[] truncated = java.util.Arrays.copyOf(gzip(controlTar(CONTROL)), 30);
		assertInvalid(ar("debian-binary", version, "control.tar.gz", truncated));
		assertInvalid(ar("debian-binary", version, "control.tar.zst", new byte[] { 0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 1, 2, 3 }));
		byte[] tar = controlTar(CONTROL);
		// The size field of the control entry (the third header) set to -1
		byte[] negative = "-0000000001\0".getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(negative, 0, tar, 2 * 512 + 512 + 124, negative.length);
		assertInvalid(ar("debian-binary", version, "control.tar", tar));
	}
	
	@Test
	void xzDictionaryIsLimited() throws IOException {
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		try (XZOutputStream xz = new XZOutputStream(compressed, new LZMA2Options(1))) {
			xz.write(controlTar(CONTROL));
		}
		byte[] stream = compressed.toByteArray();
		// The block header follows the 12 byte stream header: size, flags, filter id, properties size, dictionary property,
		// padding and its crc32. Property 37 declares a dictionary of 1.5 GiB, which the decoder would allocate up front.
		assertEquals(0x21, stream[14], "lzma2 filter in the block header");
		stream[16] = 37;
		CRC32 crc = new CRC32();
		crc.update(stream, 12, 8);
		long value = crc.getValue();
		for (int i = 0; i < 4; i++) {
			stream[20 + i] = (byte) (value >>> (8 * i));
		}
		assertInvalid(ar("debian-binary", "2.0\n".getBytes(StandardCharsets.US_ASCII), "control.tar.xz", stream));
	}
	
	@Test
	void controlTextWithoutFields() throws IOException {
		Control control = Control.parse("Package: a\nFilename: pool/evil.deb\nDescription: d\n long\nSHA256: 00\n");
		assertEquals("Package: a\nDescription: d\n long\n", control.textWithout(Set.of("Filename", "SHA256")));
		assertThrows(InvalidPackageException.class, () -> Control.parse(" continuation\n"));
		assertThrows(InvalidPackageException.class, () -> Control.parse("\n\n"));
	}
	
	@Test
	void poolPath() {
		assertEquals("pool/stable/main/l/lat-test/lat-test_1.0-1_amd64.deb", DebianHandler.poolPath("stable", "main", "lat-test", "1:1.0-1", "amd64"));
		assertEquals("pool/stable/main/libf/libfoo/libfoo_2.0_all.deb", DebianHandler.poolPath("stable", "main", "libfoo", "2.0", "all"));
	}
}
