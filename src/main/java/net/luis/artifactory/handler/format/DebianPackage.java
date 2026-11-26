package net.luis.artifactory.handler.format;

import io.airlift.compress.zstd.ZstdInputStream;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.tukaani.xz.XZInputStream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Reads the control file of a Debian binary package ({@code .deb}).<br>
 * <p>
 *     A {@code .deb} is an ar archive holding {@code debian-binary}, {@code control.tar[.gz|.xz|.zst]} and {@code data.tar[...]}.
 *     Only the control archive is read, the data archive is never decompressed.
 * </p>
 */
public final class DebianPackage {

	/**
	 * Maximum number of decompressed bytes read from the control archive, guards against a compression bomb.<br>
	 */
	private static final long MAX_CONTROL_ARCHIVE = 16L * 1024 * 1024;
	/**
	 * Maximum size of the control file itself.<br>
	 */
	private static final int MAX_CONTROL_FILE = 1024 * 1024;
	private static final byte[] AR_MAGIC = "!<arch>\n".getBytes(StandardCharsets.US_ASCII);

	private DebianPackage() {}

	/**
	 * Extracts the control file from the package.<br>
	 *
	 * @param input The content of the {@code .deb}
	 * @return The control paragraph
	 * @throws InvalidPackageException If the content is not a valid Debian binary package
	 */
	@NonNull
	public static Control readControl(@NonNull InputStream input) throws IOException {
		InputStream in = new BufferedInputStream(input);
		byte[] magic = in.readNBytes(AR_MAGIC.length);
		if (!Arrays.equals(magic, AR_MAGIC)) {
			throw new InvalidPackageException("Not a Debian package (missing ar header)");
		}
		boolean versionSeen = false;
		while (true) {
			byte[] header = in.readNBytes(60);
			if (header.length == 0) {
				throw new InvalidPackageException("Debian package contains no control archive");
			}
			if (header.length < 60 || header[58] != '`' || header[59] != '\n') {
				throw new InvalidPackageException("Malformed ar member header");
			}
			String name = new String(header, 0, 16, StandardCharsets.US_ASCII).strip();
			if (name.endsWith("/")) {
				name = name.substring(0, name.length() - 1);
			}
			long size;
			try {
				size = Long.parseLong(new String(header, 48, 10, StandardCharsets.US_ASCII).strip());
			} catch (NumberFormatException e) {
				throw new InvalidPackageException("Malformed ar member size");
			}
			if (size < 0) {
				throw new InvalidPackageException("Malformed ar member size");
			}
			if ("debian-binary".equals(name)) {
				String version = new String(readFully(in, Math.min(size, 64)), StandardCharsets.US_ASCII).strip();
				skipFully(in, size - Math.min(size, 64));
				if (!version.startsWith("2.")) {
					throw new InvalidPackageException("Unsupported Debian package format: " + version);
				}
				versionSeen = true;
			} else if (name.startsWith("control.tar")) {
				if (!versionSeen) {
					throw new InvalidPackageException("Debian package does not start with debian-binary");
				}
				InputStream member = new BoundedStream(in, size);
				InputStream tar = new BoundedStream(decompress(name, member), MAX_CONTROL_ARCHIVE);
				return Control.parse(readControlFile(tar));
			} else {
				skipFully(in, size);
			}
			if (size % 2 == 1) {
				skipFully(in, 1);
			}
		}
	}

	@NonNull
	private static InputStream decompress(@NonNull String name, @NonNull InputStream member) throws IOException {
		return switch (name) {
			case "control.tar" -> member;
			case "control.tar.gz" -> new GZIPInputStream(member);
			case "control.tar.xz" -> new XZInputStream(member);
			case "control.tar.zst" -> new ZstdInputStream(member);
			default -> throw new InvalidPackageException("Unsupported control archive compression: " + name);
		};
	}

	/**
	 * Reads the {@code control} entry of a tar archive.<br>
	 */
	@NonNull
	private static String readControlFile(@NonNull InputStream tar) throws IOException {
		String longName = null;
		while (true) {
			byte[] header = tar.readNBytes(512);
			if (header.length < 512 || isZero(header)) {
				throw new InvalidPackageException("Control archive contains no control file");
			}
			char type = (char) header[156];
			long size = parseOctal(header, 124, 12);
			String name = longName != null ? longName : entryName(header);
			longName = null;
			long padded = (size + 511) / 512 * 512;
			if (type == 'L') {
				// GNU long name, the name of the next entry is the content of this one
				byte[] content = readFully(tar, Math.min(size, 4096));
				skipFully(tar, padded - Math.min(size, 4096));
				longName = new String(content, StandardCharsets.UTF_8).replace("\0", "");
				continue;
			}
			if (type == 'x') {
				byte[] content = readFully(tar, Math.min(size, 65536));
				skipFully(tar, padded - Math.min(size, 65536));
				longName = paxPath(new String(content, StandardCharsets.UTF_8));
				continue;
			}
			String normalized = name.startsWith("./") ? name.substring(2) : name;
			if ((type == '0' || type == '\0') && "control".equals(normalized)) {
				if (size > MAX_CONTROL_FILE) {
					throw new InvalidPackageException("Control file is too large");
				}
				return new String(readFully(tar, size), StandardCharsets.UTF_8);
			}
			skipFully(tar, padded);
		}
	}

	@NonNull
	private static String entryName(byte @NonNull [] header) {
		String name = cString(header, 0, 100);
		String magic = cString(header, 257, 6);
		if (magic.startsWith("ustar")) {
			String prefix = cString(header, 345, 155);
			if (!prefix.isEmpty()) {
				return prefix + "/" + name;
			}
		}
		return name;
	}

	@Nullable
	private static String paxPath(@NonNull String records) {
		for (String record : records.split("\n")) {
			int space = record.indexOf(' ');
			if (space < 0) {
				continue;
			}
			String keyValue = record.substring(space + 1);
			if (keyValue.startsWith("path=")) {
				return keyValue.substring("path=".length());
			}
		}
		return null;
	}

	@NonNull
	private static String cString(byte @NonNull [] buffer, int offset, int length) {
		int end = offset;
		while (end < offset + length && buffer[end] != 0) {
			end++;
		}
		return new String(buffer, offset, end - offset, StandardCharsets.UTF_8);
	}

	private static long parseOctal(byte @NonNull [] buffer, int offset, int length) throws InvalidPackageException {
		if ((buffer[offset] & 0x80) != 0) {
			// Base-256 encoding of large sizes, never used for a control file
			throw new InvalidPackageException("Control archive entry is too large");
		}
		String text = cString(buffer, offset, length).strip();
		if (text.isEmpty()) {
			return 0;
		}
		try {
			return Long.parseLong(text, 8);
		} catch (NumberFormatException e) {
			throw new InvalidPackageException("Malformed tar header");
		}
	}

	private static boolean isZero(byte @NonNull [] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static byte @NonNull [] readFully(@NonNull InputStream in, long size) throws IOException {
		byte[] data = in.readNBytes((int) size);
		if (data.length < size) {
			throw new InvalidPackageException("Debian package is truncated");
		}
		return data;
	}

	private static void skipFully(@NonNull InputStream in, long size) throws IOException {
		try {
			in.skipNBytes(size);
		} catch (EOFException e) {
			throw new InvalidPackageException("Debian package is truncated");
		}
	}

	/**
	 * A control paragraph: fields in the order of the file, names compared without regard to case.<br>
	 *
	 * @param text The paragraph as written in the package, normalized to end with a single newline
	 * @param fields The field values, continuation lines joined with newlines
	 */
	public record Control(@NonNull String text, @NonNull Map<String, String> fields) {

		/**
		 * Parses the first paragraph of a control file.<br>
		 */
		@NonNull
		public static Control parse(@NonNull String content) throws InvalidPackageException {
			Map<String, String> fields = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			StringBuilder text = new StringBuilder();
			String current = null;
			for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
				if (line.isBlank()) {
					if (!fields.isEmpty()) {
						break;
					}
					continue;
				}
				if (line.startsWith("#")) {
					continue;
				}
				if (line.startsWith(" ") || line.startsWith("\t")) {
					if (current == null) {
						throw new InvalidPackageException("Malformed control file: continuation line without field");
					}
					fields.put(current, fields.get(current) + "\n" + line);
				} else {
					int colon = line.indexOf(':');
					if (colon <= 0) {
						throw new InvalidPackageException("Malformed control file: " + line);
					}
					current = line.substring(0, colon).strip();
					fields.put(current, line.substring(colon + 1).strip());
				}
				text.append(line).append('\n');
			}
			if (fields.isEmpty()) {
				throw new InvalidPackageException("Control file is empty");
			}
			return new Control(text.toString(), Collections.unmodifiableMap(fields));
		}

		@Nullable
		public String get(@NonNull String field) {
			return this.fields.get(field);
		}

		/**
		 * Returns the paragraph without the given fields (and their continuation lines).<br>
		 */
		@NonNull
		public String textWithout(@NonNull Set<String> excluded) {
			StringBuilder builder = new StringBuilder();
			boolean skipping = false;
			for (String line : this.text.split("\n")) {
				if (line.startsWith(" ") || line.startsWith("\t")) {
					if (!skipping) {
						builder.append(line).append('\n');
					}
					continue;
				}
				int colon = line.indexOf(':');
				String name = colon < 0 ? line : line.substring(0, colon).strip();
				skipping = excluded.stream().anyMatch(name::equalsIgnoreCase);
				if (!skipping) {
					builder.append(line).append('\n');
				}
			}
			return builder.toString();
		}
	}

	public static final class InvalidPackageException extends IOException {

		public InvalidPackageException(@NonNull String message) {
			super(message);
		}
	}

	/**
	 * Reads at most a number of bytes from a stream without closing it, so the rest of the archive stays readable.<br>
	 */
	private static final class BoundedStream extends FilterInputStream {

		private long remaining;

		private BoundedStream(@NonNull InputStream input, long limit) {
			super(input);
			this.remaining = limit;
		}

		@Override
		public int read() throws IOException {
			if (this.remaining <= 0) {
				return -1;
			}
			int value = super.read();
			if (value >= 0) {
				this.remaining--;
			}
			return value;
		}

		@Override
		public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
			if (this.remaining <= 0) {
				return -1;
			}
			int read = super.read(buffer, offset, (int) Math.min(length, this.remaining));
			if (read > 0) {
				this.remaining -= read;
			}
			return read;
		}

		@Override
		public long skip(long n) throws IOException {
			long skipped = super.skip(Math.min(n, this.remaining));
			this.remaining -= skipped;
			return skipped;
		}

		@Override
		public void close() {}
	}
}
