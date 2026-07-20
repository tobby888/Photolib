package cn.photolib.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageCompressorTests {
    private final ImageCompressor compressor = new ImageCompressor();

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsSmallPngFormatAndAlpha() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, new Color(255, 0, 0, 64).getRGB());
        byte[] sourceBytes = encode(image, "png");
        Path source = writeSource(sourceBytes, ".png");

        ImageCompressor.FileResult result = compress(source, "image/png", 10_000);

        assertThat(Files.readAllBytes(result.path())).isEqualTo(sourceBytes);
        assertThat(result.width()).isEqualTo(20);
        assertThat(result.height()).isEqualTo(20);
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(ImageIO.read(result.path().toFile()).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void compressesJpegBelowTarget() throws Exception {
        BufferedImage image = noisyImage(1000, 1000);
        Path source = writeSource(encode(image, "jpg"), ".jpg");

        ImageCompressor.FileResult result = compress(source, "image/jpeg", 80_000);

        assertThat(result.size()).isLessThanOrEqualTo(80_000);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void slightlyOversizedJpegKeepsOriginalDimensions() throws Exception {
        Path original = writeSource(encode(noisyImage(1200, 800), "jpg"), ".jpg");
        ImageCompressor.FileResult source = thumbnail(original, "image/jpeg", 1200, 0.82);
        long target = thumbnail(source.path(), "image/jpeg", 1200, 0.82).size();

        ImageCompressor.FileResult result = compress(source.path(), "image/jpeg", target);

        assertThat(result.size()).isLessThanOrEqualTo(target);
        assertThat(result.width()).isEqualTo(1200);
        assertThat(result.height()).isEqualTo(800);
    }

    @Test
    void usesConfiguredJpegQualityForPreview() throws Exception {
        Path source = writeSource(encode(noisyImage(800, 600), "jpg"), ".jpg");

        ImageCompressor.FileResult lowerQuality = thumbnail(source, "image/jpeg", 480, 0.6);
        ImageCompressor.FileResult higherQuality = thumbnail(source, "image/jpeg", 480, 0.9);

        assertThat(lowerQuality.size()).isLessThan(higherQuality.size());
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
        Path source = writeSource(encode(image, "png"), ".png");

        ImageCompressor.FileResult result = thumbnail(source, "image/png", 200, 0.6);
        byte[] output = Files.readAllBytes(result.path());
        BufferedImage decoded = ImageIO.read(result.path().toFile());

        assertThat(output).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
        assertThat(result.width()).isEqualTo(200);
        assertThat(result.height()).isEqualTo(150);
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void heavilyCompressedJpegPrefersModerateQualityAndAdaptiveResize() throws Exception {
        Path source = writeSource(encode(noisyImage(1600, 1200), "jpg"), ".jpg");

        ImageCompressor.FileResult result = compress(source, "image/jpeg", 180_000);

        assertThat(result.size()).isLessThanOrEqualTo(180_000);
        assertThat(result.width()).isLessThan(1600).isGreaterThan(320);
        assertThat(result.height()).isLessThan(1200).isGreaterThan(320);
    }

    @Test
    void compressesCameraSizedJpegAndBuildsPreview() throws Exception {
        Path source = writeSource(encode(noisyImage(4000, 3000), "jpg"), ".jpg");

        ImageCompressor.FileResult result = compress(source, "image/jpeg", 1_500_000);
        ImageCompressor.FileResult preview = thumbnail(result.path(), "image/jpeg", 480, 0.6);

        assertThat(result.size()).isLessThanOrEqualTo(1_500_000);
        assertThat(Files.readAllBytes(result.path())).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(result.width()).isGreaterThan(320).isLessThanOrEqualTo(4000);
        assertThat(result.height()).isGreaterThan(320).isLessThanOrEqualTo(3000);
        assertThat(preview.width()).isEqualTo(480);
        assertThat(preview.height()).isBetween(1, 480);
        assertThat(ImageIO.read(preview.path().toFile())).isNotNull();
    }

    @Test
    void rejectsOversizedPixelDimensionsBeforeDecode() throws Exception {
        byte[] png = encode(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png");
        writeInt(png, 16, ImageCompressor.MAX_DIMENSION + 1);
        writeInt(png, 20, ImageCompressor.MAX_DIMENSION + 1);
        Path source = writeSource(png, ".png");
        Path destination = temporaryDirectory.resolve("oversized-output.png");

        assertThatThrownBy(() -> compressor.compress(
                source, destination, "image/png", png.length + 1L))
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

    @Test
    void processesImagesThroughUnicodeFilePaths() throws Exception {
        Path source = temporaryDirectory.resolve("输入图片-活动现场.jpg");
        Path compressed = temporaryDirectory.resolve("成品图片.jpg");
        Path preview = temporaryDirectory.resolve("预览图片.jpg");
        Files.write(source, encode(noisyImage(1600, 1200), "jpg"));

        ImageCompressor.FileResult result = compressor.compress(
                source, compressed, "image/jpeg", 180_000);
        ImageCompressor.FileResult thumbnail = compressor.thumbnail(
                compressed, preview, "image/jpeg", 480, 0.6);

        assertThat(result.path()).isEqualTo(compressed);
        assertThat(result.size()).isEqualTo(Files.size(compressed)).isLessThanOrEqualTo(180_000);
        assertThat(thumbnail.path()).isEqualTo(preview);
        assertThat(thumbnail.size()).isEqualTo(Files.size(preview));
        assertThat(thumbnail.width()).isEqualTo(480);
        assertThat(ImageIO.read(preview.toFile())).isNotNull();
    }

    private ImageCompressor.FileResult compress(Path source, String contentType,
                                                long targetBytes) throws Exception {
        Path destination = temporaryDirectory.resolve(UUID.randomUUID()
                + ("image/png".equals(contentType) ? ".png" : ".jpg"));
        return compressor.compress(source, destination, contentType, targetBytes);
    }

    private ImageCompressor.FileResult thumbnail(Path source, String contentType,
                                                 int maxDimension, double quality) throws Exception {
        Path destination = temporaryDirectory.resolve(UUID.randomUUID()
                + ("image/png".equals(contentType) ? ".png" : ".jpg"));
        return compressor.thumbnail(source, destination, contentType, maxDimension, quality);
    }

    private Path writeSource(byte[] bytes, String extension) throws Exception {
        Path source = temporaryDirectory.resolve(UUID.randomUUID() + extension);
        return Files.write(source, bytes);
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
