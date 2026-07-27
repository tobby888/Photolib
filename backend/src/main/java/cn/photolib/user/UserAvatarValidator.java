package cn.photolib.user;

import cn.photolib.common.error.BusinessException;
import cn.photolib.common.error.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Component
class UserAvatarValidator {
    static final long MAX_BYTES = 1024L * 1024;
    static final int MAX_DIMENSION = 1024;
    private static final float JPEG_QUALITY = 0.9f;
    private static final Set<String> IMAGE_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE);

    ValidatedAvatar validate(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择头像图片");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "头像图片不能超过 1 MB");
        }

        String contentType = file.getContentType() == null
                ? "" : file.getContentType().trim().toLowerCase(Locale.ROOT);
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, "头像仅支持 JPEG 或 PNG 图片");
        }

        byte[] bytes = file.getBytes();
        if (bytes.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择头像图片");
        }
        if (bytes.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "头像图片不能超过 1 MB");
        }
        if (!matchesSignature(bytes, contentType)) {
            throw unsupported("头像内容与声明的图片类型不匹配");
        }

        DecodedImage decoded = decode(bytes, contentType);
        byte[] normalized = encode(decoded.image(), contentType);
        if (normalized.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                    "规范化后的头像不能超过 1 MB，请降低图片质量后重试");
        }
        String extension = MediaType.IMAGE_PNG_VALUE.equals(contentType) ? "png" : "jpg";
        return new ValidatedAvatar(normalized, contentType, extension,
                decoded.image().getWidth(), decoded.image().getHeight());
    }

    private DecodedImage decode(byte[] bytes, String expectedType) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw unsupported("无法识别头像图片");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw unsupported("无法识别头像图片");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!matchesReaderFormat(format, expectedType)) {
                    throw unsupported("头像内容与声明的图片类型不匹配");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1) {
                    throw unsupported("无法识别头像图片尺寸");
                }
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "头像尺寸不能超过 1024 × 1024 像素");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw unsupported("无法解码头像图片");
                }
                return new DecodedImage(toStandardColorModel(image, expectedType));
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw unsupported("头像图片已损坏或无法解码");
        }
    }

    private BufferedImage toStandardColorModel(BufferedImage source, String contentType) {
        boolean preserveAlpha = MediaType.IMAGE_PNG_VALUE.equals(contentType)
                && source.getColorModel().hasAlpha();
        BufferedImage normalized = new BufferedImage(source.getWidth(), source.getHeight(),
                preserveAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return normalized;
    }

    private byte[] encode(BufferedImage image, String contentType) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (MediaType.IMAGE_PNG_VALUE.equals(contentType)) {
                if (!ImageIO.write(image, "png", output)) {
                    throw unsupported("无法规范化 PNG 头像");
                }
                return output.toByteArray();
            }

            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) {
                throw unsupported("服务器无法编码 JPEG 头像");
            }
            ImageWriter writer = writers.next();
            try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam parameters = writer.getDefaultWriteParam();
                if (parameters.canWriteCompressed()) {
                    parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    parameters.setCompressionQuality(JPEG_QUALITY);
                }
                writer.write(null, new IIOImage(image, null, null), parameters);
            } finally {
                writer.dispose();
            }
            return output.toByteArray();
        }
    }

    private boolean matchesSignature(byte[] bytes, String contentType) {
        if (MediaType.IMAGE_JPEG_VALUE.equals(contentType)) {
            return bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        }
        byte[] pngSignature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        return bytes.length >= pngSignature.length
                && Arrays.equals(pngSignature, Arrays.copyOf(bytes, pngSignature.length));
    }

    private boolean matchesReaderFormat(String format, String contentType) {
        return MediaType.IMAGE_JPEG_VALUE.equals(contentType)
                ? "jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format)
                : "png".equalsIgnoreCase(format);
    }

    private BusinessException unsupported(String message) {
        return new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE, message);
    }

    record ValidatedAvatar(byte[] bytes, String contentType, String extension, int width, int height) {
    }

    private record DecodedImage(BufferedImage image) {
    }
}
