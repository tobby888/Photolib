package cn.photolib.common.upload;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Streams supported ZIP entries to caller-owned, randomly named managed files.
 * Entry paths are used only as display names and are never resolved on disk.
 */
@Component
public class SafeImageZipExtractor {

    /** Legacy encoding of names in archives zipped by Chinese Windows, absent on a trimmed JDK. */
    private static final Charset LEGACY_CHINESE_CHARSET = legacyChineseCharset();

    /** Extracts with the gallery quota, for signed-in bulk uploads. */
    public List<ExtractedImage> extract(InputStream source, DestinationFactory destinations)
            throws IOException {
        return extract(source, destinations, Limits.gallery(), null);
    }

    /** Applies a caller-specific Unicode code-point display-name limit. */
    public List<ExtractedImage> extract(InputStream source, DestinationFactory destinations,
                                        int maxDisplayNameCodePoints) throws IOException {
        if (maxDisplayNameCodePoints < 1) {
            throw new IllegalArgumentException("文件名长度上限必须大于 0");
        }
        return extract(source, destinations, Limits.gallery(),
                Integer.valueOf(maxDisplayNameCodePoints));
    }

    /**
     * Extracts under caller-supplied limits. Anonymous paths must pass their own
     * quota rather than inheriting the gallery's, which is sized for members.
     */
    public List<ExtractedImage> extract(InputStream source, DestinationFactory destinations,
                                        Limits limits) throws IOException {
        return extract(source, destinations, limits, limits.maxFileNameCodePoints());
    }

    private List<ExtractedImage> extract(InputStream source, DestinationFactory destinations,
                                         Limits limits,
                                         Integer maxDisplayNameCodePoints) throws IOException {
        List<ExtractedImage> extracted = new ArrayList<>();
        long expandedTotal = 0;
        // ISO-8859-1 keeps non-UTF-8 entry names as raw bytes for decodeEntryName instead of
        // letting ZipInputStream reject the archive; UTF-8-flagged names bypass this charset.
        try (ZipInputStream zip = new ZipInputStream(source, StandardCharsets.ISO_8859_1)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = validateEntryPath(decodeEntryName(entry.getName()));
                if (entry.isDirectory() || entryName.endsWith("/")) continue;
                if (isMacOsMetadata(entryName)) continue;
                String baseName = baseName(entryName);
                String originalFileName = maxDisplayNameCodePoints == null
                        ? ImageUploadPolicy.safeDisplayFileName(baseName)
                        : ImageUploadPolicy.safeDisplayFileName(baseName, maxDisplayNameCodePoints);
                String contentType = ImageUploadPolicy.contentTypeFromFileName(originalFileName);
                if (contentType == null) continue;
                if (extracted.size() >= limits.maxImageCount()) {
                    throw new IllegalArgumentException(
                            "ZIP 内图片超过 " + limits.maxImageCount() + " 张");
                }

                Path destination = destinations.create(ImageUploadPolicy.extension(contentType));
                try {
                    CopiedFile copied = copyLimited(zip, destination, limits);
                    expandedTotal = Math.addExact(expandedTotal, copied.size());
                    if (expandedTotal > limits.maxExpandedBytes()) {
                        throw new IllegalArgumentException(
                                "ZIP 解压总大小超过 " + ImageUploadPolicy.describe(limits.maxExpandedBytes()));
                    }
                    extracted.add(new ExtractedImage(originalFileName, destination,
                            contentType, copied.size(), copied.sha256()));
                } catch (ArithmeticException exception) {
                    deleteQuietly(destination);
                    throw new IllegalArgumentException(
                            "ZIP 解压总大小超过 " + ImageUploadPolicy.describe(limits.maxExpandedBytes()), exception);
                } catch (IOException | RuntimeException exception) {
                    deleteQuietly(destination);
                    throw exception;
                }
            }
            if (extracted.isEmpty()) {
                throw new IllegalArgumentException("ZIP 中没有 JPG/PNG 图片");
            }
            return List.copyOf(extracted);
        } catch (IOException | RuntimeException exception) {
            extracted.forEach(image -> deleteQuietly(image.localFile()));
            throw exception;
        }
    }

    /**
     * Recovers the entry name written by the zipping tool. A name flagged as UTF-8 has already
     * been decoded by {@link ZipInputStream} and is returned untouched; anything else arrives as
     * raw bytes (see the ISO-8859-1 stream charset) and is decoded as UTF-8, then as the legacy
     * Chinese encoding, keeping the raw form when neither applies. Decoding happens before path
     * validation because a GBK trail byte can be {@code 0x5C}, which would otherwise be read as
     * a directory separator.
     */
    static String decodeEntryName(String rawName) {
        if (rawName == null) return null;
        if (!rawName.chars().allMatch(character -> character <= 0xFF)) return rawName;
        byte[] bytes = rawName.getBytes(StandardCharsets.ISO_8859_1);
        boolean ascii = true;
        for (byte value : bytes) {
            if (value < 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) return rawName;
        String utf8 = strictDecode(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) return utf8;
        String legacy = LEGACY_CHINESE_CHARSET == null
                ? null : strictDecode(bytes, LEGACY_CHINESE_CHARSET);
        return legacy != null ? legacy : rawName;
    }

    private static String strictDecode(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notThisCharset) {
            return null;
        }
    }

    private static Charset legacyChineseCharset() {
        for (String name : new String[] {"GBK", "GB18030"}) {
            if (Charset.isSupported(name)) return Charset.forName(name);
        }
        return null;
    }

    static String validateEntryPath(String rawName) {
        if (rawName == null || rawName.isEmpty() || rawName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("ZIP 包含非法路径");
        }
        String normalized = rawName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("//")
                || normalized.matches("(?i)^[a-z]:.*")) {
            throw new IllegalArgumentException("ZIP 包含非法路径");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.equals("..") || segment.matches("(?i)^[a-z]:.*")) {
                throw new IllegalArgumentException("ZIP 包含非法路径");
            }
        }
        return normalized;
    }

    /**
     * macOS Finder "Compress" writes an AppleDouble companion ({@code ._IMG_4082.JPG}) for every
     * file, usually under {@code __MACOSX/}. They carry the image's extension but hold resource-fork
     * metadata, so treating them as images makes each one a photo that fails magic-number
     * validation, and they count against the image quota. Skipped before the quota check.
     */
    static boolean isMacOsMetadata(String normalizedName) {
        for (String segment : normalizedName.split("/")) {
            if (segment.equalsIgnoreCase("__MACOSX")) return true;
        }
        int slash = normalizedName.lastIndexOf('/');
        return normalizedName.startsWith("._", slash + 1);
    }

    private String baseName(String normalizedName) {
        int slash = normalizedName.lastIndexOf('/');
        String result = normalizedName.substring(slash + 1);
        if (result.isBlank()) throw new IllegalArgumentException("ZIP 包含非法路径");
        return result;
    }

    private CopiedFile copyLimited(InputStream input, Path destination, Limits limits)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 不支持 SHA-256", impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream file = Files.newOutputStream(destination);
             DigestOutputStream output = new DigestOutputStream(file, digest)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > limits.maxImageBytes()) {
                    throw new IllegalArgumentException(
                            "单张图片超过 " + ImageUploadPolicy.describe(limits.maxImageBytes()));
                }
                output.write(buffer, 0, read);
            }
        }
        return new CopiedFile(total, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Per-archive extraction quota.
     *
     * @param maxImageCount          images accepted before the archive is rejected
     * @param maxImageBytes          largest single extracted image
     * @param maxExpandedBytes       total expanded size, the guard against a zip bomb
     * @param maxFileNameCodePoints  display-name truncation limit
     */
    public record Limits(int maxImageCount, long maxImageBytes, long maxExpandedBytes,
                         int maxFileNameCodePoints) {
        public Limits {
            if (maxImageCount < 1 || maxImageBytes < 1 || maxExpandedBytes < 1
                    || maxFileNameCodePoints < 1) {
                throw new IllegalArgumentException("ZIP 解压限额必须为正数");
            }
        }

        public static Limits gallery() {
            return new Limits(ImageUploadPolicy.MAX_IMAGE_COUNT, ImageUploadPolicy.MAX_IMAGE_BYTES,
                    ImageUploadPolicy.MAX_EXPANDED_BYTES, 255);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Caller also performs workspace cleanup; retain the original error.
        }
    }

    @FunctionalInterface
    public interface DestinationFactory {
        Path create(String extension) throws IOException;
    }

    public record ExtractedImage(String originalFileName, Path localFile,
                                 String contentType, long size, String sha256) {
    }

    private record CopiedFile(long size, String sha256) {
    }
}
