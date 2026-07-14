package cn.photolib.photo;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

@Component
public class ImageCompressor {
    private static final float JPEG_MIN_QUALITY = 0.82f;
    private static final float JPEG_MAX_QUALITY = 0.97f;
    private static final int MIN_DIMENSION = 320;

    public Result compress(byte[] source, String contentType, long targetBytes) throws IOException {
        // Most camera JPEGs are already below the 10 MiB target. Read only image
        // metadata in that case: a full ImageIO decode expands a compressed photo
        // to tens or hundreds of MiB and caused concurrent uploads to exhaust the
        // JVM heap even though no recompression was needed.
        if (source.length <= targetBytes) {
            Dimensions dimensions = dimensions(source);
            return new Result(source, dimensions.width(), dimensions.height(), contentType);
        }

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            throw new IOException("无法解析图片");
        }
        String format = contentType.equals("image/png") ? "png" : "jpeg";
        byte[] output = format.equals("jpeg")
                ? compressJpeg(image, targetBytes)
                : compressPng(image, targetBytes);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(output));
        return new Result(output, result.getWidth(), result.getHeight(), contentType);
    }

    private Dimensions dimensions(byte[] source) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) throw new IOException("无法解析图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("无法解析图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new Dimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    public Result thumbnail(byte[] source, String contentType, int maxDimension) throws IOException {
        return thumbnail(source, contentType, maxDimension, 0.82);
    }

    public Result thumbnail(byte[] source, String contentType, int maxDimension,
                            double compressionRatio) throws IOException {
        if (compressionRatio <= 0 || compressionRatio > 1) {
            throw new IllegalArgumentException("预览图压缩比率必须大于 0 且不超过 1");
        }
        Dimensions original = dimensions(source);
        double scale = Math.min(1.0, (double) maxDimension / Math.max(original.width(), original.height()));
        int targetWidth = Math.max(1, (int) Math.round(original.width() * scale));
        int targetHeight = Math.max(1, (int) Math.round(original.height() * scale));
        // Decoding a full-resolution camera photo just to immediately throw most
        // of it away in resize() wastes CPU and, worse, allocates a huge pixel
        // buffer per photo (a 24MP JPEG decodes to ~100 MiB) — expensive enough
        // that running more than one of these at once can starve a small server
        // of both heap and CPU. Read the source pre-downscaled via JPEG/PNG
        // decoder subsampling instead, then do one small precise resize.
        BufferedImage decoded = scale < 1.0
                ? decodeSubsampled(source, targetWidth, targetHeight)
                : fullDecode(source);
        BufferedImage resized = decoded.getWidth() == targetWidth && decoded.getHeight() == targetHeight
                ? decoded
                : resizeTo(decoded, targetWidth, targetHeight);
        byte[] output = contentType.equals("image/png")
                ? writePng(resized)
                : writeJpeg(toRgb(resized), (float) compressionRatio);
        return new Result(output, resized.getWidth(), resized.getHeight(), contentType);
    }

    private BufferedImage fullDecode(byte[] source) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException("无法解析图片");
        return image;
    }

    private BufferedImage decodeSubsampled(byte[] source, int targetWidth, int targetHeight) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (input == null) throw new IOException("无法解析图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("无法解析图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int subsample = Math.max(1, Math.min(
                        width / Math.max(1, targetWidth), height / Math.max(1, targetHeight)));
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsample > 1) {
                    param.setSourceSubsampling(subsample, subsample, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) throw new IOException("无法解析图片");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private byte[] compressJpeg(BufferedImage image, long target) throws IOException {
        BufferedImage current = toRgb(image);
        while (true) {
            byte[] best = highestQualityJpeg(current, target, JPEG_MIN_QUALITY, JPEG_MAX_QUALITY);
            if (best != null) return best;

            byte[] minimumQuality = writeJpeg(current, JPEG_MIN_QUALITY);
            double scale = Math.sqrt((double) target / minimumQuality.length) * 0.98;
            scale = Math.max(0.75, Math.min(0.95, scale));
            if (scaledBelowMinimum(current, scale)) {
                BufferedImage minimumSize = resizeToMinimum(current);
                byte[] lastResort = highestQualityJpeg(minimumSize, target, 0.40f, JPEG_MIN_QUALITY);
                return lastResort != null ? lastResort : writeJpeg(minimumSize, 0.40f);
            }
            current = resize(current, scale);
        }
    }

    private byte[] compressPng(BufferedImage image, long target) throws IOException {
        BufferedImage current = image;
        byte[] output = writePng(current);
        if (output.length <= target) return output;
        while (current.getWidth() > MIN_DIMENSION && current.getHeight() > MIN_DIMENSION) {
            double scale = Math.sqrt((double) target / output.length) * 0.98;
            scale = Math.max(0.75, Math.min(0.95, scale));
            if (scaledBelowMinimum(current, scale)) {
                current = resizeToMinimum(current);
            } else {
                current = resize(current, scale);
            }
            output = writePng(current);
            if (output.length <= target) return output;
        }
        return output;
    }

    private byte[] highestQualityJpeg(BufferedImage image, long target,
                                      float minimumQuality, float maximumQuality) throws IOException {
        byte[] minimum = writeJpeg(image, minimumQuality);
        if (minimum.length > target) return null;

        byte[] best = minimum;
        float low = minimumQuality;
        float high = maximumQuality;
        for (int i = 0; i < 9; i++) {
            float quality = (low + high) / 2;
            byte[] candidate = writeJpeg(image, quality);
            if (candidate.length <= target) {
                best = candidate;
                low = quality;
            } else {
                high = quality;
            }
        }
        return best;
    }

    private boolean scaledBelowMinimum(BufferedImage image, double scale) {
        return image.getWidth() * scale < MIN_DIMENSION
                || image.getHeight() * scale < MIN_DIMENSION;
    }

    private BufferedImage resizeToMinimum(BufferedImage image) {
        double scale = Math.max((double) MIN_DIMENSION / image.getWidth(),
                (double) MIN_DIMENSION / image.getHeight());
        return scale < 1.0 ? resize(image, scale) : image;
    }

    private byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private byte[] writePng(BufferedImage image) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }

    private BufferedImage resize(BufferedImage source, double scale) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        return resizeTo(source, width, height);
    }

    private BufferedImage resizeTo(BufferedImage source, int width, int height) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) return source;
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    public record Result(byte[] bytes, int width, int height, String contentType) {
    }

    private record Dimensions(int width, int height) {
    }
}
