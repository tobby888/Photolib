package cn.photolib.common.upload;

import java.util.Locale;
import java.util.Set;

/**
 * The limits and file-name/MIME rules shared by gallery and recruitment image
 * uploads. Keeping these values in one place prevents the two ZIP paths from
 * drifting apart.
 */
public final class ImageUploadPolicy {
    public static final int MAX_IMAGE_COUNT = 100;
    public static final long MAX_IMAGE_BYTES = 100L * 1024 * 1024;
    public static final long MAX_EXPANDED_BYTES = 10L * 1024 * 1024 * 1024;
    public static final long MAX_ARCHIVE_BYTES = 1_500_000_000L;

    private static final Set<String> SUPPORTED_TYPES = Set.of("image/jpeg", "image/png");

    private ImageUploadPolicy() {
    }

    public static boolean supportedContentType(String contentType) {
        return SUPPORTED_TYPES.contains(contentType);
    }

    /** Returns the MIME inferred from a supported file extension, or {@code null}. */
    public static String contentTypeFromFileName(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return null;
    }

    public static boolean fileNameMatchesContentType(String fileName, String contentType) {
        return contentType != null && contentType.equals(contentTypeFromFileName(fileName));
    }

    /**
     * Renders a byte limit the way the UI states it, so an error message and the
     * constant behind it can be checked against each other at a glance.
     *
     * <p>Exact binary multiples read as GiB / MiB. Limits are administrator
     * managed, so a round decimal value such as 1 500 000 000 reads as "1.5 GB"
     * rather than a string of digits, anything else from 1 MiB up gets at most two
     * decimals of the binary unit ("2.5 MiB"), and smaller values read as KiB.
     * Mirrors {@code describeBytes} in the frontend.</p>
     */
    public static String describe(long bytes) {
        long kib = 1024L;
        long mib = 1024L * kib;
        long gib = 1024L * mib;
        if (bytes >= gib && bytes % gib == 0) return (bytes / gib) + " GiB";
        if (bytes >= mib && bytes % mib == 0) return (bytes / mib) + " MiB";
        if (bytes >= 1_000_000_000L && bytes % 100_000_000L == 0) return decimal(bytes / 1e9) + " GB";
        if (bytes >= 1_000_000L && bytes % 1_000_000L == 0) return (bytes / 1_000_000L) + " MB";
        if (bytes >= gib) return decimal((double) bytes / gib) + " GiB";
        if (bytes >= mib) return decimal((double) bytes / mib) + " MiB";
        if (bytes >= kib && bytes % kib == 0) return (bytes / kib) + " KiB";
        return bytes + " 字节";
    }

    /** Up to two decimals with trailing zeros dropped: 1.50 → "1.5", 2.00 → "2". */
    private static String decimal(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    public static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> throw new IllegalArgumentException("仅支持 JPG 和 PNG");
        };
    }

    /** Removes path separators/control characters from a user-facing file name. */
    public static String safeDisplayFileName(String fileName) {
        if (fileName == null) throw new IllegalArgumentException("文件名不能为空");
        StringBuilder cleaned = new StringBuilder();
        fileName.codePoints().forEach(codePoint -> {
            if (codePoint == '/' || codePoint == '\\' || Character.isISOControl(codePoint)) {
                cleaned.append('_');
            } else {
                cleaned.appendCodePoint(codePoint);
            }
        });
        String value = cleaned.toString().trim();
        if (value.isEmpty()) throw new IllegalArgumentException("文件名不能为空");
        return value;
    }

    /** Truncates by Unicode code point while preserving a short file extension. */
    public static String safeDisplayFileName(String fileName, int maxCodePoints) {
        if (maxCodePoints < 1) throw new IllegalArgumentException("文件名长度上限必须大于 0");
        String value = safeDisplayFileName(fileName);
        if (value.codePointCount(0, value.length()) <= maxCodePoints) return value;
        int dot = value.lastIndexOf('.');
        String extension = dot > 0 && value.substring(dot).codePointCount(0, value.length() - dot) <= 16
                ? value.substring(dot) : "";
        int extensionPoints = extension.codePointCount(0, extension.length());
        int baseLimit = Math.max(1, maxCodePoints - extensionPoints);
        String base = extension.isEmpty() ? value : value.substring(0, dot);
        int end = base.offsetByCodePoints(0, Math.min(baseLimit,
                base.codePointCount(0, base.length())));
        return base.substring(0, end) + extension;
    }
}
