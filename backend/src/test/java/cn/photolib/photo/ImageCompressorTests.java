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

    private byte[] encode(BufferedImage image, String format) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        }
    }
}
