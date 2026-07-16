package cn.photolib.photo;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void resizesPngPreviewAndPreservesAlpha() throws Exception {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, new Color(x & 255, y & 255, (x + y) & 255,
                        (x * 3 + y) & 255).getRGB());
            }
        }

        ImageCompressor.Result result = compressor.thumbnail(
                encode(image, "png"), "image/png", 200, 0.6);
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(result.bytes()));

        assertThat(result.bytes()).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(result.width()).isEqualTo(200);
        assertThat(result.height()).isEqualTo(150);
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
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

    @Test
    void compressesCameraSizedJpegAndBuildsPreview() throws Exception {
        byte[] source = encode(noisyImage(4000, 3000), "jpg");

        ImageCompressor.Result result = compressor.compress(source, "image/jpeg", 1_500_000);
        ImageCompressor.Result preview = compressor.thumbnail(
                result.bytes(), result.contentType(), 480, 0.6);

        assertThat(result.bytes().length).isLessThanOrEqualTo(1_500_000);
        assertThat(result.bytes()).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(result.width()).isGreaterThan(320).isLessThanOrEqualTo(4000);
        assertThat(result.height()).isGreaterThan(320).isLessThanOrEqualTo(3000);
        assertThat(preview.width()).isEqualTo(480);
        assertThat(preview.height()).isBetween(1, 480);
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(preview.bytes()))).isNotNull();
    }

    @Test
    void rejectsOversizedPixelDimensionsBeforeDecode() throws Exception {
        byte[] png = encode(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png");
        writeInt(png, 16, ImageCompressor.MAX_DIMENSION + 1);
        writeInt(png, 20, ImageCompressor.MAX_DIMENSION + 1);

        assertThatThrownBy(() -> compressor.compress(png, "image/png", png.length + 1L))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("安全上限");
    }

    @Test
    void packagesBothWindowsAndLinuxNativeLibraries() {
        assertThat(ImageCompressor.class.getResource(
                "/native/windows-x86_64/photolib-image.dll")).isNotNull();
        assertThat(ImageCompressor.class.getResource(
                "/native/linux-x86_64/libphotolib-image.so")).isNotNull();
    }

    private void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private BufferedImage noisyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int red = (x * 31 + y) & 255;
                int green = (x + y * 17) & 255;
                int blue = (x * y) & 255;
                pixels[y * width + x] = (red << 16) | (green << 8) | blue;
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
