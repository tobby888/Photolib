package cn.photolib.photo;

import cn.photolib.common.upload.ImageSignature;
import cn.photolib.common.upload.ImageUploadPolicy;
import cn.photolib.common.upload.SafeImageZipExtractor;
import cn.photolib.common.upload.UnprocessableImageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动图（多帧 GIF、APNG）：认得出来、成品图原样保存、缩略图取静态首帧。
 * 压缩器只会产出单帧图片，把动图送进去会悄悄丢掉第一帧以外的全部内容。
 */
class AnimatedImagesTests {
    private final ImageCompressor compressor = new ImageCompressor();

    @TempDir
    Path directory;

    @Test
    void countsGifFramesWithoutDecodingPixels() throws Exception {
        Path animated = write(AnimatedFixtures.gif(64, 48, Color.RED, Color.GREEN, Color.BLUE), ".gif");
        Path still = write(AnimatedFixtures.gif(64, 48, Color.RED), ".gif");

        AnimatedImages.Info info = AnimatedImages.inspect(animated, "image/gif");
        assertThat(info.gif()).isTrue();
        assertThat(info.animated()).isTrue();
        assertThat(info.frames()).isEqualTo(3);
        assertThat(info.width()).isEqualTo(64);
        assertThat(info.height()).isEqualTo(48);

        AnimatedImages.Info single = AnimatedImages.inspect(still, "image/gif");
        assertThat(single.animated()).isFalse();
        // 单帧 GIF 也原样保存：压缩器不会写 GIF。
        assertThat(single.keepAsIs()).isTrue();
    }

    @Test
    void countsTheFramesOfATruncatedGifTheWayBrowsersShowThem() throws Exception {
        byte[] full = AnimatedFixtures.gif(32, 32, Color.RED, Color.GREEN, Color.BLUE);
        // 去掉结束符、第三帧的子块结束符和一个数据字节：第三帧不完整，前两帧完整。
        Path truncated = write(Arrays.copyOf(full, full.length - 3), ".gif");

        AnimatedImages.Info info = AnimatedImages.inspect(truncated, "image/gif");
        assertThat(info.animated()).isTrue();
        assertThat(info.frames()).isEqualTo(2);
    }

    @Test
    void rejectsGifCanvasesTooLargeToDecodeTheFirstFrameSafely() throws Exception {
        byte[] header = new byte[13];
        System.arraycopy("GIF89a".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 6);
        header[6] = (byte) (5000 & 0xff);
        header[7] = (byte) (5000 >> 8);
        header[8] = (byte) (5000 & 0xff);
        header[9] = (byte) (5000 >> 8);
        Path huge = write(header, ".gif");

        assertThatThrownBy(() -> AnimatedImages.inspect(huge, "image/gif"))
                .isInstanceOf(UnprocessableImageException.class)
                .satisfies(error -> assertThat(((UnprocessableImageException) error).uploaderMessage())
                        .contains("4096×4096"));
    }

    @Test
    void recognisesApngOnlyByAnActlChunkBeforeTheFirstIdat() throws Exception {
        byte[] png = AnimatedFixtures.noisyPng(40, 30);
        Path plain = write(png, ".png");
        Path apng = write(AnimatedFixtures.withActl(png, 4, false), ".png");
        Path oneFrame = write(AnimatedFixtures.withActl(png, 1, false), ".png");
        Path lateActl = write(AnimatedFixtures.withActl(png, 4, true), ".png");

        assertThat(AnimatedImages.inspect(plain, "image/png").keepAsIs()).isFalse();
        AnimatedImages.Info info = AnimatedImages.inspect(apng, "image/png");
        assertThat(info.animated()).isTrue();
        assertThat(info.frames()).isEqualTo(4);
        assertThat(info.gif()).isFalse();
        assertThat(AnimatedImages.inspect(oneFrame, "image/png").animated()).isFalse();
        assertThat(AnimatedImages.inspect(lateActl, "image/png").animated()).isFalse();
        assertThat(AnimatedImages.inspect(plain, "image/jpeg").keepAsIs()).isFalse();
    }

    @Test
    void keepsAnimatedGifBytesExactlyRegardlessOfTheTargetSize() throws Exception {
        byte[] bytes = AnimatedFixtures.gif(320, 200, Color.RED, Color.GREEN, Color.BLUE);
        Path source = write(bytes, ".gif");

        ImageCompressor.FileResult result = compressor.compress(
                source, directory.resolve("out/processed.gif"), "image/gif", 1);

        assertThat(Files.readAllBytes(result.path())).isEqualTo(bytes);
        assertThat(result.contentType()).isEqualTo("image/gif");
        assertThat(result.width()).isEqualTo(320);
        assertThat(result.height()).isEqualTo(200);
    }

    @Test
    void keepsApngBytesEvenWhenLargerThanTheFinishedTarget() throws Exception {
        byte[] bytes = AnimatedFixtures.withActl(AnimatedFixtures.noisyPng(300, 200), 3, false);
        Path source = write(bytes, ".png");
        AnimatedImages.Info info = AnimatedImages.inspect(source, "image/png");

        ImageCompressor.FileResult result = compressor.keepAsIs(
                source, directory.resolve("out/processed.png"), "image/png", info);
        // 走 compress 也一样：以前超过目标体积的 APNG 会被重新编码成单帧 PNG。
        ImageCompressor.FileResult viaCompress = compressor.compress(
                source, directory.resolve("out/compressed.png"), "image/png", bytes.length / 4);

        assertThat(Files.readAllBytes(result.path())).isEqualTo(bytes);
        assertThat(Files.readAllBytes(viaCompress.path())).isEqualTo(bytes);
        assertThat(result.width()).isEqualTo(300);
        assertThat(result.height()).isEqualTo(200);
    }

    @Test
    void buildsAStillWebpThumbnailFromTheFirstGifFrame() throws Exception {
        Path source = write(AnimatedFixtures.gif(960, 480, Color.RED, Color.BLUE), ".gif");
        Path destination = directory.resolve("out/thumbnail.webp");

        ImageCompressor.FileResult thumbnail = compressor.thumbnail(source, destination, "image/gif", 480, 0.6);

        assertThat(thumbnail.contentType()).isEqualTo("image/webp");
        assertThat(thumbnail.width()).isEqualTo(480);
        assertThat(thumbnail.height()).isEqualTo(240);
        byte[] header = Arrays.copyOf(Files.readAllBytes(destination), 12);
        assertThat(new String(header, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(header, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WEBP");
        // 中间产物（首帧 PNG）不能留在任务目录里。
        try (var files = Files.list(destination.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("thumbnail.webp");
        }
    }

    @Test
    void buildsAThumbnailFromTheDefaultImageOfAnApng() throws Exception {
        Path source = write(AnimatedFixtures.withActl(AnimatedFixtures.noisyPng(600, 300), 3, false), ".png");

        ImageCompressor.FileResult thumbnail = compressor.thumbnail(
                source, directory.resolve("out/apng.webp"), "image/png", 480, 0.6);

        assertThat(thumbnail.width()).isEqualTo(480);
        assertThat(thumbnail.height()).isEqualTo(240);
    }

    @Test
    void validatesGifMagicAgainstTheDeclaredType() throws Exception {
        Path gif = write(AnimatedFixtures.gif(8, 8, Color.RED), ".gif");
        Path png = write(AnimatedFixtures.noisyPng(8, 8), ".png");

        PhotoProcessingService.validateMagic(gif, "image/gif");
        assertThatThrownBy(() -> PhotoProcessingService.validateMagic(png, "image/gif"))
                .hasMessageContaining("不一致");
        assertThat(ImageSignature.detect(gif)).isEqualTo("image/gif");
    }

    @Test
    void onlyTheGalleryAcceptsGifNamesAndTypes() {
        assertThat(ImageUploadPolicy.galleryFileNameMatchesContentType("dance.GIF", "image/gif")).isTrue();
        assertThat(ImageUploadPolicy.galleryFileNameMatchesContentType("dance.gif", "image/png")).isFalse();
        assertThat(ImageUploadPolicy.galleryFileNameMatchesContentType("a.jpg", "image/jpeg")).isTrue();
        // 公开招募仍只收 JPG / PNG。
        assertThat(ImageUploadPolicy.fileNameMatchesContentType("dance.gif", "image/gif")).isFalse();
        assertThat(ImageUploadPolicy.extension("image/gif")).isEqualTo(".gif");
    }

    @Test
    void galleryZipKeepsGifEntriesWhileRecruitmentZipSkipsThem() throws Exception {
        byte[] gif = AnimatedFixtures.gif(8, 8, Color.RED, Color.BLUE);
        byte[] png = AnimatedFixtures.noisyPng(8, 8);
        byte[] archive = zip("dance.gif", gif, "still.png", png);
        SafeImageZipExtractor extractor = new SafeImageZipExtractor();
        SafeImageZipExtractor.DestinationFactory destinations =
                extension -> directory.resolve(UUID.randomUUID() + extension);

        var gallery = extractor.extractKeepingNames(new ByteArrayInputStream(archive), destinations,
                SafeImageZipExtractor.Limits.gallery());
        var recruitment = extractor.extract(new ByteArrayInputStream(archive), destinations,
                SafeImageZipExtractor.Limits.gallery());

        assertThat(gallery).extracting(SafeImageZipExtractor.ExtractedImage::contentType)
                .containsExactly("image/gif", "image/png");
        assertThat(gallery.get(0).localFile().toString()).endsWith(".gif");
        assertThat(recruitment).extracting(SafeImageZipExtractor.ExtractedImage::contentType)
                .containsExactly("image/png");
    }

    private Path write(byte[] bytes, String extension) throws Exception {
        Path path = directory.resolve(UUID.randomUUID() + extension);
        Files.write(path, bytes);
        return path;
    }

    private static byte[] zip(String firstName, byte[] first, String secondName, byte[] second)
            throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry(firstName));
            output.write(first);
            output.closeEntry();
            output.putNextEntry(new ZipEntry(secondName));
            output.write(second);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
