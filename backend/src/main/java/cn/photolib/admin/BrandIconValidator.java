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
    private static final Set<String> IMAGE_TYPES = Set.of(
            MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE);

    NormalizedIcon normalize(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择图标文件");
        }
        if (file.getSize() > MAX_ICON_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "图标不能超过 512 KiB");
        }
        String contentType = file.getContentType();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "图标仅支持 PNG 或 JPEG");
        }

        BufferedImage image = readIcon(file.getBytes());
        try (ByteArrayOutputStream normalized = new ByteArrayOutputStream()) {
            String format = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? "png" : "jpeg";
            if (!ImageIO.write(image, format, normalized)) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法规范化图标图片");
            }
            return new NormalizedIcon(normalized.toByteArray(), contentType);
        }
    }

    private BufferedImage readIcon(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法识别图标图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) > 1024 || reader.getHeight(0) > 1024) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "图标尺寸不能超过 1024 × 1024 像素");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "无法识别图标图片");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    record NormalizedIcon(byte[] bytes, String contentType) {
    }
}
