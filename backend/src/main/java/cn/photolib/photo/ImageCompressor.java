package cn.photolib.photo;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Component
public class ImageCompressor {

    public Result compress(byte[] source, String contentType, long targetBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) {
            throw new IOException("无法解析图片");
        }
        String format = contentType.equals("image/png") ? "png" : "jpeg";
        if (source.length <= targetBytes) {
            return new Result(source, image.getWidth(), image.getHeight(), contentType);
        }
        byte[] output = format.equals("jpeg")
                ? compressJpeg(image, targetBytes)
                : compressPng(image, targetBytes);
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(output));
        return new Result(output, result.getWidth(), result.getHeight(), contentType);
    }

    public Result thumbnail(byte[] source, String contentType, int maxDimension) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null) throw new IOException("无法解析图片");
        double scale = Math.min(1.0, (double) maxDimension / Math.max(image.getWidth(), image.getHeight()));
        BufferedImage resized = scale < 1.0 ? resize(image, scale) : image;
        byte[] output = contentType.equals("image/png") ? writePng(resized) : writeJpeg(toRgb(resized), 0.82f);
        return new Result(output, resized.getWidth(), resized.getHeight(), contentType);
    }

    private byte[] compressJpeg(BufferedImage image, long target) throws IOException {
        BufferedImage current = toRgb(image);
        while (true) {
            byte[] best = null;
            float low = 0.15f;
            float high = 0.95f;
            for (int i = 0; i < 9; i++) {
                float quality = (low + high) / 2;
                byte[] candidate = writeJpeg(current, quality);
                if (candidate.length <= target) {
                    best = candidate;
                    low = quality;
                } else {
                    high = quality;
                }
            }
            if (best != null) return best;
            current = resize(current, 0.85);
            if (current.getWidth() < 320 || current.getHeight() < 320) {
                return writeJpeg(current, 0.15f);
            }
        }
    }

    private byte[] compressPng(BufferedImage image, long target) throws IOException {
        BufferedImage current = image;
        byte[] output = writePng(current);
        while (output.length > target && current.getWidth() >= 320 && current.getHeight() >= 320) {
            double low = 0.5;
            double high = 0.95;
            byte[] best = null;
            BufferedImage bestImage = null;
            for (int i = 0; i < 7; i++) {
                double scale = (low + high) / 2;
                BufferedImage candidateImage = resize(current, scale);
                byte[] candidate = writePng(candidateImage);
                if (candidate.length <= target) {
                    best = candidate;
                    bestImage = candidateImage;
                    low = scale;
                } else {
                    high = scale;
                }
            }
            if (best != null) return best;
            current = bestImage == null ? resize(current, 0.5) : bestImage;
            output = writePng(current);
        }
        return output;
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
}
