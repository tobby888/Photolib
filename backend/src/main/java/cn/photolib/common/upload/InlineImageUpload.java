package cn.photolib.common.upload;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;

/**
 * The shared rules for images embedded in written content — 选题/需求说明的插图和
 * 文档中心的插图走的是同一套限制。
 *
 * <p>与 {@link ImageUploadPolicy} 的区别：那一套管的是图库里的作品原图（上百 MiB、
 * 只收 JPEG/PNG、要留原图）。这里管的是正文插图，体积小、允许 WebP、按内容分发，
 * 两者的上限和格式集合刻意不共用，改动一边不会波及另一边。</p>
 *
 * <p>声明的 Content-Type 必须和文件头一致：浏览器上传的 MIME 完全由客户端提供，
 * 只信它就等于让人把任意字节存成 {@code image/png} 再由服务端原样回吐。</p>
 */
public final class InlineImageUpload {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp");

    private InlineImageUpload() {
    }

    /** 校验并读出字节；任何不合规的输入都以 BusinessException 结束。 */
    public static byte[] read(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "插图不能超过 " + ImageUploadPolicy.describe(MAX_BYTES));
        }
        String contentType = file.getContentType();
        if (!TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "仅支持 JPEG、PNG 或 WebP 图片");
        }
        byte[] bytes = file.getBytes();
        if (!matchesSignature(bytes, contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "图片内容与文件类型不匹配");
        }
        return bytes;
    }

    public static String extension(String contentType) {
        return switch (contentType) {
            case MediaType.IMAGE_JPEG_VALUE -> "jpg";
            case MediaType.IMAGE_PNG_VALUE -> "png";
            case "image/webp" -> "webp";
            default -> throw new IllegalArgumentException("不支持的插图类型: " + contentType);
        };
    }

    public static boolean matchesSignature(byte[] bytes, String contentType) {
        if (MediaType.IMAGE_JPEG_VALUE.equals(contentType)) {
            return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        }
        if (MediaType.IMAGE_PNG_VALUE.equals(contentType)) {
            byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            return bytes.length >= signature.length
                    && Arrays.equals(signature, Arrays.copyOf(bytes, signature.length));
        }
        if (!"image/webp".equals(contentType)) return false;
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
}
