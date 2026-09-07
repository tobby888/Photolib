package cn.photolib.admin;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;

@Component
public class BrandIconValidator {
    public static final long MAX_ICON_BYTES = 512 * 1024;
    public static final long MAX_PLACEHOLDER_BYTES = 3L * 1024 * 1024;
    private static final int MAX_ICON_PIXELS = 1024;
    private static final int MAX_PLACEHOLDER_PIXELS = 4096;
    private static final Set<String> IMAGE_TYPES = Set.of(
            MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE);

    NormalizedIcon normalize(MultipartFile file) throws IOException {
        return normalize(file, new Limits("图标", MAX_ICON_BYTES, "512 KiB", MAX_ICON_PIXELS));
    }

    /** 占位图铺在图片卡片上，比图标宽松：允许 3 MiB、4096 像素见方。 */
    NormalizedIcon normalizePlaceholder(MultipartFile file) throws IOException {
        return normalize(file, new Limits("占位图", MAX_PLACEHOLDER_BYTES, "3 MiB", MAX_PLACEHOLDER_PIXELS));
    }

    private NormalizedIcon normalize(MultipartFile file, Limits limits) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择" + limits.label() + "文件");
        }
        if (file.getSize() > limits.maxBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    limits.label() + "不能超过 " + limits.maxBytesText());
        }
        String contentType = file.getContentType();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, limits.label() + "仅支持 PNG 或 JPEG");
        }

        BufferedImage image = read(file.getBytes(), limits);
        try (ByteArrayOutputStream normalized = new ByteArrayOutputStream()) {
            String format = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? "png" : "jpeg";
            if (!ImageIO.write(image, format, normalized)) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法规范化" + limits.label() + "图片");
            }
            return new NormalizedIcon(normalized.toByteArray(), contentType);
        }
    }

    private BufferedImage read(byte[] bytes, Limits limits) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法识别" + limits.label() + "图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) > limits.maxPixels() || reader.getHeight(0) > limits.maxPixels()) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            limits.label() + "尺寸不能超过 " + limits.maxPixels() + " × " + limits.maxPixels() + " 像素");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法识别" + limits.label() + "图片");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private record Limits(String label, long maxBytes, String maxBytesText, int maxPixels) {
    }

    record NormalizedIcon(byte[] bytes, String contentType) {
    }
}
