package cn.photolib.photo;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ImageCompressorTests {
    private final ImageCompressor compressor = new ImageCompressor();

    @Test
    void keepsSmallPngFormatAndAlpha() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, new Color(255, 0, 0, 64).getRGB());
        byte[] source = encode(image, "png");

        ImageCompressor.Result result = compressor.compress(source, "image/png", 10_000);

        assertThat(result.bytes()).isSameAs(source);
        assertThat(result.width()).isEqualTo(20);
        assertThat(result.height()).isEqualTo(20);
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.bytes()))
                .getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void compressesJpegBelowTarget() throws Exception {
        BufferedImage image = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color((x * 31 + y) & 255, (x + y * 17) & 255,
                        (x * y) & 255).getRGB());
            }
        }
        byte[] source = encode(image, "jpg");

        ImageCompressor.Result result = compressor.compress(source, "image/jpeg", 80_000);

        assertThat(result.bytes().length).isLessThanOrEqualTo(80_000);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void slightlyOversizedJpegKeepsOriginalDimensions() throws Exception {
        BufferedImage image = noisyImage(1200, 800);
        byte[] source = compressor.thumbnail(encode(image, "jpg"), "image/jpeg", 1200).bytes();
        long target = compressor.thumbnail(source, "image/jpeg", 1200).bytes().length;

        ImageCompressor.Result result = compressor.compress(source, "image/jpeg", target);

        assertThat((long) result.bytes().length).isLessThanOrEqualTo(target);
        assertThat(result.width()).isEqualTo(1200);
        assertThat(result.height()).isEqualTo(800);
    }

    @Test
    void usesConfiguredJpegQualityForPreview() throws Exception {
        byte[] source = encode(noisyImage(800, 600), "jpg");

        ImageCompressor.Result lowerQuality = compressor.thumbnail(source, "image/jpeg", 480, 0.6);
        ImageCompressor.Result higherQuality = compressor.thumbnail(source, "image/jpeg", 480, 0.9);

        assertThat(lowerQuality.bytes().length).isLessThan(higherQuality.bytes().length);
        assertThat(lowerQuality.width()).isEqualTo(480);
        assertThat(lowerQuality.height()).isEqualTo(360);
    }

    @Test
    void heavilyCompressedJpegPrefersModerateQualityAndAdaptiveResize() throws Exception {
        BufferedImage image = noisyImage(1600, 1200);
        byte[] source = encode(image, "jpg");

        ImageCompressor.Result result = compressor.compress(source, "image/jpeg", 180_000);

        assertThat(result.bytes().length).isLessThanOrEqualTo(180_000);
        assertThat(result.width()).isLessThan(1600).isGreaterThan(320);
        assertThat(result.height()).isLessThan(1200).isGreaterThan(320);
    }

    private BufferedImage noisyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, new Color((x * 31 + y) & 255, (x + y * 17) & 255,
                        (x * y) & 255).getRGB());
            }
        }
        return image;
    }

    private byte[] encode(BufferedImage image, String format) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        }
    }
}
