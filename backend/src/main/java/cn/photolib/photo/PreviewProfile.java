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
    public static final String CURRENT_GENERATOR_FINGERPRINT =
            "hybrid-v4/max480/legacy-le128m-orient1/"
                    + "vips8.18.3-ge100mp-ge30k-bufgt128m-orientne1-down-ar-"
                    + "j420optstrip-pngc9strip";

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

    public String effectiveQuality(String contentType) {
        if ("image/png".equals(contentType)) {
            return "lossless";
        }
        if ("image/jpeg".equals(contentType)) {
            return Long.toString(Math.round(compressionRatio.doubleValue() * 100.0d));
        }
        throw new IllegalArgumentException("不支持的预览图类型: " + contentType);
    }

    public String fingerprint() {
        return generatorFingerprint + "|ratio=" + ratioText();
    }

    public Map<String, String> objectMetadata(String contentType, String sha256) {
        String normalizedSha256 = normalizeSha256(sha256);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_RATIO, ratioText());
        metadata.put(METADATA_EFFECTIVE_QUALITY, effectiveQuality(contentType));
        metadata.put(METADATA_GENERATOR, generatorFingerprint);
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
        Map<String, String> metadata = object.userMetadata();
        return ratioText().equals(metadata.get(METADATA_RATIO))
                && effectiveQuality(expectedContentType).equals(
                metadata.get(METADATA_EFFECTIVE_QUALITY))
                && generatorFingerprint.equals(metadata.get(METADATA_GENERATOR))
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
