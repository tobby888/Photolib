package cn.photolib.photo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

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
        updatePngHeaderCrc(png);
        Path source = writeSource(png, ".png");
        Path destination = temporaryDirectory.resolve("oversized-output.png");

        assertThatThrownBy(() -> compressor.compress(
                source, destination, "image/png", png.length + 1L))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("安全上限");
    }

    @Test
    void rejectsHugeProgressiveJpegBeforePixelDecode() throws Exception {
        byte[] jpeg = encodeProgressiveJpeg(noisyImage(32, 24));
        int frame = findJpegStartOfFrame(jpeg);
        writeUnsignedShort(jpeg, frame + 5, 24);
        writeUnsignedShort(jpeg, frame + 7, 30_000);
        Path source = writeSource(jpeg, ".jpg");

        assertThatThrownBy(() -> compressor.thumbnail(source,
                temporaryDirectory.resolve("progressive-output.jpg"),
                "image/jpeg", 480, 0.6))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("超大渐进式 JPEG");
    }

    @Test
    void rejectsInterlacedRgbaPngWhoseDecodedBufferExceedsOneHundredTwentyEightMib()
            throws Exception {
        byte[] png = encodeProgressivePng(new BufferedImage(
                32, 24, BufferedImage.TYPE_INT_ARGB));
        writeInt(png, 16, 6_000);
        writeInt(png, 20, 6_000);
        updatePngHeaderCrc(png);
        Path source = writeSource(png, ".png");

        assertThatThrownBy(() -> compressor.thumbnail(source,
                temporaryDirectory.resolve("interlaced-output.png"),
                "image/png", 480, 0.6))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("超大隔行 PNG");
    }

    @Test
    @Timeout(120)
    void streamsLargeTransparentPngBelowLegacyPixelAndDimensionThresholds()
            throws Exception {
        int width = 6_000;
        int height = 6_000;
        Path source = writeStreamingRgbaPng(width, height);

        ImageCompressor.FileResult result = thumbnail(
                source, "image/png", 480, 0.6);
        BufferedImage decoded = ImageIO.read(result.path().toFile());

        assertThat((long) width * height).isLessThan(100_000_000L);
        assertThat((long) width * height * 4).isGreaterThan(128L * 1024 * 1024);
        assertThat(width).isLessThan(30_000);
        assertThat(result.width()).isEqualTo(480);
        assertThat(result.height()).isEqualTo(480);
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void nativeBoundaryRejectsInputLargerThanOneHundredMib() throws Exception {
        Path source = writeSource(encode(new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_RGB), "png"), ".png");
        try (RandomAccessFile sparse = new RandomAccessFile(source.toFile(), "rw")) {
            sparse.setLength(ImageCompressor.MAX_INPUT_BYTES + 1);
        }

        assertThatThrownBy(() -> NativeImageProcessor.instance()
                .dimensions(source, "image/png"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("100 MiB 原生安全上限");
    }

    @Test
    @Timeout(120)
    void compressesRealImageAboveOneHundredMegapixelsWithBoundedFirstCandidate()
            throws Exception {
        int width = 12_000;
        int height = 9_000;
        Path source = writeStreamingRgbPng(width, height);
        long target = Math.max(64_000, Files.size(source) / 3);

        ImageCompressor.FileResult result = compress(source, "image/png", target);

        assertThat((long) width * height).isGreaterThan(100_000_000L);
        assertThat(Files.size(source)).isLessThan(ImageCompressor.MAX_INPUT_BYTES);
        assertThat(result.size()).isLessThanOrEqualTo(target);
        assertThat(result.width()).isLessThan(width).isGreaterThan(320);
        assertThat(result.height()).isLessThan(height).isGreaterThan(320);
        assertThat(Files.readAllBytes(result.path()))
                .startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
    }

    @Test
    void processesUltraWideBaselineJpegThroughVips() throws Exception {
        Path source = writeSource(encode(noisyImage(32_001, 2), "jpg"), ".jpg");

        ImageCompressor.FileResult result = thumbnail(
                source, "image/jpeg", 480, 0.72);

        assertThat(result.width()).isEqualTo(480);
        assertThat(result.height()).isEqualTo(1);
        assertThat(result.size()).isPositive();
        assertThat(Files.readAllBytes(result.path()))
                .startsWith((byte) 0xff, (byte) 0xd8);
    }

    @Test
    void autoRotatesSmallOrientationSixAndEightJpegsForCompressionAndThumbnail()
            throws Exception {
        for (int orientation : new int[]{6, 8}) {
            byte[] jpeg = injectExifOrientation(
                    encodeJpegAtQuality(orientationTestImage(), 1.0f), orientation);
            Path source = writeSource(jpeg, ".jpg");

            ImageCompressor.FileResult compressed = compress(
                    source, "image/jpeg", Files.size(source) - 1);
            ImageCompressor.FileResult thumbnail = thumbnail(
                    source, "image/jpeg", 60, 0.82);

            assertThat(compressed.width()).as("compressed width, orientation " + orientation)
                    .isEqualTo(80);
            assertThat(compressed.height()).as("compressed height, orientation " + orientation)
                    .isEqualTo(120);
            assertThat(thumbnail.width()).as("thumbnail width, orientation " + orientation)
                    .isEqualTo(40);
            assertThat(thumbnail.height()).as("thumbnail height, orientation " + orientation)
                    .isEqualTo(60);
            assertRotatedQuadrants(ImageIO.read(compressed.path().toFile()), orientation);
            assertRotatedQuadrants(ImageIO.read(thumbnail.path().toFile()), orientation);
        }
    }

    @Test
    void preservesSmallJpegFastPathButReportsExifOrientedDimensions() throws Exception {
        byte[] jpeg = injectExifOrientation(
                encodeJpegAtQuality(orientationTestImage(), 1.0f), 6);
        Path source = writeSource(jpeg, ".jpg");
        Path destination = temporaryDirectory.resolve("orientation-fast-copy.jpg");

        ImageCompressor.FileResult result = compressor.compress(
                source, destination, "image/jpeg", Files.size(source));

        assertThat(result.width()).isEqualTo(80);
        assertThat(result.height()).isEqualTo(120);
        assertThat(Files.readAllBytes(destination)).containsExactly(jpeg);
    }

    @Test
    void packagesBothWindowsAndLinuxNativeLibrariesAndLicenseTexts() throws Exception {
        assertThat(ImageCompressor.class.getResource(
                "/native/windows-x86_64/photolib-image.dll")).isNotNull();
        assertThat(ImageCompressor.class.getResource(
                "/native/linux-x86_64/libphotolib-image.so")).isNotNull();
        assertThat(ImageCompressor.class.getResource(
                "/native/windows-x86_64/libvips-42.dll")).isNotNull();
        assertThat(ImageCompressor.class.getResource(
                "/native/linux-x86_64/libvips-cpp.so.8.18.3")).isNotNull();
        assertThat(ImageCompressor.class.getResource(
                "/native/THIRD_PARTY_NOTICES.txt")).isNotNull();
        for (String platform : new String[]{"windows-x64", "linux-x64"}) {
            for (String manifest : new String[]{"README.md", "package.json", "versions.json"}) {
                assertThat(ImageCompressor.class.getResource(
                        "/native/licenses/sharp-libvips/" + platform + "/" + manifest))
                        .as(platform + " " + manifest)
                        .isNotNull();
            }
        }
        assertThat(resourceText("/native/licenses/libjpeg-turbo/LICENSE.md"))
                .contains("Independent JPEG Group", "Redistribution and use");
        assertThat(resourceText("/native/licenses/libjpeg-turbo/README.ijg"))
                .contains("Independent JPEG Group's JPEG software");
        assertThat(resourceText("/native/licenses/stb/LICENSE"))
                .contains("MIT License", "Permission is hereby granted");
        assertThat(resourceText("/native/licenses/common/GPL-3.0.txt"))
                .contains("GNU GENERAL PUBLIC LICENSE");
        assertThat(resourceText("/native/licenses/common/LGPL-3.0.txt"))
                .contains("GNU LESSER GENERAL PUBLIC LICENSE");
        assertThat(resourceText("/native/licenses/common/MPL-2.0.txt"))
                .contains("Mozilla Public License Version 2.0");
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

    private void writeUnsignedShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 8);
        bytes[offset + 1] = (byte) value;
    }

    private void updatePngHeaderCrc(byte[] png) {
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        writeInt(png, 29, (int) crc.getValue());
    }

    private int findJpegStartOfFrame(byte[] jpeg) {
        for (int index = 0; index + 8 < jpeg.length; index++) {
            if ((jpeg[index] & 0xff) == 0xff &&
                    ((jpeg[index + 1] & 0xff) == 0xc0 ||
                            (jpeg[index + 1] & 0xff) == 0xc2)) {
                return index;
            }
        }
        throw new IllegalArgumentException("JPEG 中缺少 SOF 标记");
    }

    private byte[] encodeProgressiveJpeg(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private byte[] encodeJpegAtQuality(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private byte[] injectExifOrientation(byte[] jpeg, int orientation) throws Exception {
        ByteArrayOutputStream exifBytes = new ByteArrayOutputStream();
        try (DataOutputStream exif = new DataOutputStream(exifBytes)) {
            exif.write(new byte[]{'E', 'x', 'i', 'f', 0, 0});
            exif.write(new byte[]{'I', 'I', 0x2a, 0});
            exif.write(new byte[]{8, 0, 0, 0});
            exif.write(new byte[]{1, 0});
            exif.write(new byte[]{0x12, 0x01, 3, 0});
            exif.write(new byte[]{1, 0, 0, 0});
            exif.write(new byte[]{(byte) orientation, 0, 0, 0});
            exif.write(new byte[]{0, 0, 0, 0});
        }
        byte[] payload = exifBytes.toByteArray();
        ByteArrayOutputStream result = new ByteArrayOutputStream(jpeg.length + payload.length + 4);
        try (DataOutputStream output = new DataOutputStream(result)) {
            output.write(jpeg, 0, 2);
            output.writeByte(0xff);
            output.writeByte(0xe1);
            output.writeShort(payload.length + 2);
            output.write(payload);
            output.write(jpeg, 2, jpeg.length - 2);
        }
        return result.toByteArray();
    }

    private BufferedImage orientationTestImage() {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 0, 60, 40);
            graphics.setColor(Color.GREEN);
            graphics.fillRect(60, 0, 60, 40);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 40, 60, 40);
            graphics.setColor(Color.YELLOW);
            graphics.fillRect(60, 40, 60, 40);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void assertRotatedQuadrants(BufferedImage image, int orientation) {
        Color[] expected = orientation == 6
                ? new Color[]{Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN}
                : new Color[]{Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE};
        assertColorNear(image, image.getWidth() / 4, image.getHeight() / 4, expected[0]);
        assertColorNear(image, image.getWidth() * 3 / 4, image.getHeight() / 4, expected[1]);
        assertColorNear(image, image.getWidth() / 4, image.getHeight() * 3 / 4, expected[2]);
        assertColorNear(image, image.getWidth() * 3 / 4,
                image.getHeight() * 3 / 4, expected[3]);
    }

    private void assertColorNear(BufferedImage image, int x, int y, Color expected) {
        Color actual = new Color(image.getRGB(x, y));
        int distance = Math.abs(actual.getRed() - expected.getRed())
                + Math.abs(actual.getGreen() - expected.getGreen())
                + Math.abs(actual.getBlue() - expected.getBlue());
        assertThat(distance)
                .as("RGB distance at (%s,%s): actual=%s expected=%s", x, y, actual, expected)
                .isLessThan(90);
    }

    private byte[] encodeProgressivePng(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), parameters);
            output.flush();
            return bytes.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private Path writeStreamingRgbPng(int width, int height) throws Exception {
        return writeStreamingPng(width, height, 3);
    }

    private Path writeStreamingRgbaPng(int width, int height) throws Exception {
        return writeStreamingPng(width, height, 4);
    }

    private Path writeStreamingPng(int width, int height, int channels) throws Exception {
        Path compressedPixels = temporaryDirectory.resolve("large-image-idat.bin");
        byte[] row = new byte[Math.multiplyExact(width, channels) + 1];
        if (channels == 4) {
            for (int x = 0; x < width; x++) {
                row[1 + x * channels + 3] = (byte) 128;
            }
        }
        try (OutputStream file = Files.newOutputStream(compressedPixels);
             DeflaterOutputStream deflated = new DeflaterOutputStream(
                     file, new Deflater(Deflater.BEST_SPEED), 64 * 1024)) {
            for (int y = 0; y < height; y++) {
                row[1] = (byte) y;
                deflated.write(row);
            }
        }

        Path png = temporaryDirectory.resolve(
                "large-" + width + "x" + height + "-" + channels + "ch.png");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(png))) {
            output.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47,
                    0x0d, 0x0a, 0x1a, 0x0a});
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(13);
            try (DataOutputStream header = new DataOutputStream(headerBytes)) {
                header.writeInt(width);
                header.writeInt(height);
                header.writeByte(8);
                header.writeByte(channels == 4 ? 6 : 2);
                header.writeByte(0);
                header.writeByte(0);
                header.writeByte(0);
            }
            writePngChunk(output, "IHDR", headerBytes.toByteArray());
            writePngFileChunk(output, "IDAT", compressedPixels);
            writePngChunk(output, "IEND", new byte[0]);
        }
        return png;
    }

    private void writePngChunk(DataOutputStream output, String type, byte[] data)
            throws Exception {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeInt(data.length);
        output.write(typeBytes);
        output.write(data);
        output.writeInt((int) crc.getValue());
    }

    private void writePngFileChunk(DataOutputStream output, String type, Path data)
            throws Exception {
        long size = Files.size(data);
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("测试 PNG IDAT 过大");
        }
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        output.writeInt((int) size);
        output.write(typeBytes);
        try (var input = Files.newInputStream(data)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                output.write(buffer, 0, read);
                crc.update(buffer, 0, read);
            }
        }
        output.writeInt((int) crc.getValue());
    }

    private String resourceText(String path) throws Exception {
        try (var input = ImageCompressorTests.class.getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
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
