package cn.photolib.photo;

import cn.photolib.storage.ObjectStorageService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The complete, reproducible contract for one preview generation. The ratio is
 * deliberately normalized to four decimal places so the environment, database
 * and object metadata can be compared without floating-point ambiguity.
 */
public record PreviewProfile(BigDecimal compressionRatio, String generatorFingerprint) {
    /**
     * The umbrella identity stored in {@code preview_setting}. Bump it whenever
     * <em>any</em> preview encoder changes; the startup audit compares it to
     * decide that a generation switch is due at all.
     */
    public static final String CURRENT_GENERATOR_FINGERPRINT =
            "hybrid-v6/max480/webp-q-effort6-smartsubsample-strip";

    /**
     * The one container every preview is encoded into. It is part of the profile
     * identity: a preview object whose own MIME is anything else belongs to an
     * older generation, however well the rest of its metadata matches.
     */
    public static final String PREVIEW_CONTENT_TYPE = ImageCompressor.PREVIEW_CONTENT_TYPE;

    /**
     * The encoder identity recorded on each preview object.
     *
     * <p>Deliberately a separate constant from the umbrella above. An encoder
     * change usually touches one format at a time, and every object whose
     * recorded identity still matches is already the new generation — keeping
     * the two apart is what lets a single-format change leave the rest of the
     * library in place instead of re-encoding and re-uploading all of it. There
     * is one preview format today, so there is one constant; add a sibling
     * rather than making this one derive from the umbrella.</p>
     */
    static final String WEBP_OBJECT_GENERATOR =
            "hybrid-v6/max480/vips8.18.3-down-ar-webp-q-effort6-smartsubsample-strip";

    public static final String METADATA_RATIO = "photolib-preview-ratio";
    public static final String METADATA_EFFECTIVE_QUALITY = "photolib-preview-effective-quality";
    public static final String METADATA_GENERATOR = "photolib-preview-generator";
    public static final String METADATA_SHA256 = "photolib-preview-sha256";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public PreviewProfile {
        compressionRatio = normalizeRatio(compressionRatio);
        if (generatorFingerprint == null || generatorFingerprint.isBlank()) {
            throw new IllegalArgumentException("预览图生成器指纹不能为空");
        }
        generatorFingerprint = generatorFingerprint.trim();
        if (generatorFingerprint.length() > 128) {
            throw new IllegalArgumentException("预览图生成器指纹不能超过 128 个字符");
        }
    }

    public static PreviewProfile configured(double ratio) {
        if (!Double.isFinite(ratio)) {
            throw new IllegalArgumentException("PREVIEW_COMPRESSION_RATIO 必须是有限数值");
        }
        return new PreviewProfile(BigDecimal.valueOf(ratio), CURRENT_GENERATOR_FINGERPRINT);
    }

    public static BigDecimal normalizeRatio(BigDecimal ratio) {
        Objects.requireNonNull(ratio, "compressionRatio");
        if (ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "PREVIEW_COMPRESSION_RATIO 必须大于 0 且不超过 1");
        }
        BigDecimal normalized;
        try {
            normalized = ratio.setScale(4, RoundingMode.HALF_UP);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("预览图压缩倍率无法规范化", exception);
        }
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException("PREVIEW_COMPRESSION_RATIO 必须大于 0 且不超过 1");
        }
        return normalized;
    }

    public boolean isCurrentGenerator() {
        return CURRENT_GENERATOR_FINGERPRINT.equals(generatorFingerprint);
    }

    public String ratioText() {
        return compressionRatio.toPlainString();
    }

    /**
     * The encoder identity recorded on, and expected from, one preview object.
     * See {@link #WEBP_OBJECT_GENERATOR} for why this is format-scoped.
     */
    public String objectGenerator(String contentType) {
        requirePreviewContentType(contentType);
        return WEBP_OBJECT_GENERATOR;
    }

    /** The ratio as the encoder consumes it: libwebp's Q. */
    public String effectiveQuality(String contentType) {
        requirePreviewContentType(contentType);
        return Long.toString(Math.round(compressionRatio.doubleValue() * 100.0d));
    }

    private static void requirePreviewContentType(String contentType) {
        if (!PREVIEW_CONTENT_TYPE.equals(contentType)) {
            throw new IllegalArgumentException("不支持的预览图类型: " + contentType);
        }
    }

    public String fingerprint() {
        return generatorFingerprint + "|ratio=" + ratioText();
    }

    public Map<String, String> objectMetadata(String contentType, String sha256) {
        String normalizedSha256 = normalizeSha256(sha256);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_RATIO, ratioText());
        metadata.put(METADATA_EFFECTIVE_QUALITY, effectiveQuality(contentType));
        metadata.put(METADATA_GENERATOR, objectGenerator(contentType));
        metadata.put(METADATA_SHA256, normalizedSha256);
        return Map.copyOf(metadata);
    }

    /**
     * Validates the HEAD response only. The SHA value is required to be
     * well-formed; callers that have an independently persisted digest can pass
     * it to the overload below for an exact comparison.
     */
    public boolean matches(ObjectStorageService.ObjectInfo object, String expectedContentType) {
        if (object == null || object.size() <= 0
                || !Objects.equals(expectedContentType, object.contentType())) {
            return false;
        }
        // A preview in any other container is from an older generation, no
        // matter how well its remaining metadata lines up.
        if (!PREVIEW_CONTENT_TYPE.equals(expectedContentType)) return false;
        Map<String, String> metadata = object.userMetadata();
        return ratioText().equals(metadata.get(METADATA_RATIO))
                && effectiveQuality(expectedContentType).equals(
                metadata.get(METADATA_EFFECTIVE_QUALITY))
                && objectGenerator(expectedContentType).equals(
                metadata.get(METADATA_GENERATOR))
                && isValidSha256(metadata.get(METADATA_SHA256));
    }

    public boolean matches(ObjectStorageService.ObjectInfo object, String expectedContentType,
                           String expectedSha256) {
        return matches(object, expectedContentType)
                && normalizeSha256(expectedSha256).equals(
                object.userMetadata().get(METADATA_SHA256));
    }

    private static boolean isValidSha256(String sha256) {
        return sha256 != null && SHA256.matcher(sha256).matches();
    }

    private static String normalizeSha256(String sha256) {
        Objects.requireNonNull(sha256, "sha256");
        String normalized = sha256.trim().toLowerCase(Locale.ROOT);
        if (!isValidSha256(normalized)) {
            throw new IllegalArgumentException("预览图 SHA-256 元数据必须为 64 位十六进制");
        }
        return normalized;
    }
}
