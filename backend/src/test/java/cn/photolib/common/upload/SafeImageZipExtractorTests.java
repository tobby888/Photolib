package cn.photolib.common.upload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeImageZipExtractorTests {
    @TempDir
    Path temporaryDirectory;

    private final SafeImageZipExtractor extractor = new SafeImageZipExtractor();

    @Test
    void extractsOnlySupportedImagesAndKeepsOriginalBytesAndHash() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4};
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 9};
        byte[] archive = zip(Map.of(
                "nested/photo.jpg", jpeg,
                "nested/透明图.png", png,
                "readme.txt", new byte[] {7}));

        var result = extractor.extract(new ByteArrayInputStream(archive),
                extension -> temporaryDirectory.resolve(UUID.randomUUID() + extension), 255);

        assertThat(result).extracting(SafeImageZipExtractor.ExtractedImage::originalFileName)
                .containsExactlyInAnyOrder("photo.jpg", "透明图.png");
        for (var image : result) {
            byte[] expected = image.contentType().equals("image/jpeg") ? jpeg : png;
            assertThat(Files.readAllBytes(image.localFile())).isEqualTo(expected);
            assertThat(image.size()).isEqualTo(expected.length);
            assertThat(image.sha256()).isEqualTo(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(expected)));
        }
    }

    @Test
    void skipsMacOsAppleDoubleFilesSoTheyNeverBecomePhotosOrCountAgainstTheQuota() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4};
        // AppleDouble header: not a JPEG despite the extension.
        byte[] appleDouble = {0x00, 0x05, 0x16, 0x07, 0x00, 0x02, 0x00, 0x00};
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < 100; index++) {
            entries.put("活动/IMG_" + index + ".JPG", jpeg);
            entries.put("__MACOSX/活动/._IMG_" + index + ".JPG", appleDouble);
        }
        entries.put("._IMG_4082.JPG", appleDouble);
        entries.put("活动/._IMG_4083.png", appleDouble);
        entries.put("__macosx/stray.jpg", appleDouble);

        var result = extractor.extract(new ByteArrayInputStream(zip(entries)),
                extension -> temporaryDirectory.resolve(UUID.randomUUID() + extension));

        assertThat(result).hasSize(100);
        assertThat(result).extracting(SafeImageZipExtractor.ExtractedImage::originalFileName)
                .allSatisfy(name -> assertThat(name).startsWith("IMG_"));
        for (var image : result) {
            assertThat(Files.readAllBytes(image.localFile())).isEqualTo(jpeg);
        }
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).hasSize(100);
        }
    }

    @Test
    void anArchiveHoldingOnlyMacOsMetadataHasNoImages() {
        assertThatThrownBy(() -> extractor.extract(
                new ByteArrayInputStream(zip(Map.of("__MACOSX/._IMG_4082.JPG", new byte[] {0, 5, 22, 7}))),
                extension -> temporaryDirectory.resolve(UUID.randomUUID() + extension)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有 JPG/PNG");
    }

    @Test
    void keepsImagesWhoseNamesMerelyContainTheMetadataMarkers() {
        assertThat(SafeImageZipExtractor.isMacOsMetadata("a._b.jpg")).isFalse();
        assertThat(SafeImageZipExtractor.isMacOsMetadata("MACOSX/photo.jpg")).isFalse();
        assertThat(SafeImageZipExtractor.isMacOsMetadata("__MACOSX_backup.jpg")).isFalse();
        assertThat(SafeImageZipExtractor.isMacOsMetadata("_IMG_1.jpg")).isFalse();
        assertThat(SafeImageZipExtractor.isMacOsMetadata("folder/._IMG_1.jpg")).isTrue();
    }

    @Test
    void rejectsAbsoluteTraversalUncDriveAndNulPaths() {
        assertThatThrownBy(() -> SafeImageZipExtractor.validateEntryPath("../photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeImageZipExtractor.validateEntryPath("folder/../photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeImageZipExtractor.validateEntryPath("/photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeImageZipExtractor.validateEntryPath("\\\\server\\photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeImageZipExtractor.validateEntryPath("C:\\photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeImageZipExtractor.validateEntryPath("folder/C:\\photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeImageZipExtractor.validateEntryPath("bad\0photo.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyArchiveAndMoreThanOneHundredImages() throws Exception {
        assertThatThrownBy(() -> extractor.extract(
                new ByteArrayInputStream(zip(Map.of("readme.txt", new byte[] {1}))),
                extension -> temporaryDirectory.resolve(UUID.randomUUID() + extension)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有 JPG/PNG");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < 101; index++) {
            entries.put(index + ".jpg", new byte[] {1});
        }
        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(zip(entries)),
                extension -> temporaryDirectory.resolve(UUID.randomUUID() + extension)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过 100 张");
    }

    @Test
    void sanitizesAndLimitsZipDisplayNameTo255UnicodeCodePoints() throws Exception {
        String longName = "😀".repeat(300) + "\u0001name.jpg";
        byte[] archive = zip(Map.of(longName, new byte[] {1, 2, 3}));

        var result = extractor.extract(new ByteArrayInputStream(archive),
                extension -> temporaryDirectory.resolve(UUID.randomUUID() + extension), 255);

        String displayName = result.getFirst().originalFileName();
        assertThat(displayName.codePointCount(0, displayName.length())).isLessThanOrEqualTo(255);
        assertThat(displayName).endsWith(".jpg").doesNotContain("/", "\\", "\u0001");
    }

    @Test
    void readsChineseNamesFromAnArchiveZippedByWindowsWithoutTheUtf8Flag() throws Exception {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4};
        byte[] archive = zip(Map.of("DSC00680-编辑.jpg", jpeg), Charset.forName("GBK"));

        var result = extractor.extract(new ByteArrayInputStream(archive),
                extension -> temporaryDirectory.resolve(UUID.randomUUID() + extension), 255);

        assertThat(result).extracting(SafeImageZipExtractor.ExtractedImage::originalFileName)
                .containsExactly("DSC00680-编辑.jpg");
        assertThat(Files.readAllBytes(result.getFirst().localFile())).isEqualTo(jpeg);
    }

    @Test
    void decodesUnflaggedNamesAsUtf8FirstAndKeepsUndecodableBytesAsIs() {
        String chinese = "毕业照/合影.jpg";
        assertThat(SafeImageZipExtractor.decodeEntryName(
                new String(chinese.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)))
                .isEqualTo(chinese);
        assertThat(SafeImageZipExtractor.decodeEntryName("photo.jpg")).isEqualTo("photo.jpg");
        assertThat(SafeImageZipExtractor.decodeEntryName("café.jpg")).isEqualTo("café.jpg");
        assertThat(SafeImageZipExtractor.decodeEntryName("透明图.png")).isEqualTo("透明图.png");
    }

    private byte[] zip(Map<String, byte[]> entries) throws Exception {
        return zip(entries, StandardCharsets.UTF_8);
    }

    private byte[] zip(Map<String, byte[]> entries, Charset charset) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, charset)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
