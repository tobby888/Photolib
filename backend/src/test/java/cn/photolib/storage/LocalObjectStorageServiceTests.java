package cn.photolib.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import cn.photolib.common.util.LimitedInputStream;
import cn.photolib.common.util.UploadSizeLimitExceededException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalObjectStorageServiceTests {
    @TempDir
    Path directory;

    @Test
    void storesReadsSignsAndDeletesObjects() throws Exception {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects", "test-secret",
                java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        LocalObjectStorageService storage = new LocalObjectStorageService(properties);
        byte[] content = "photo-content".getBytes(StandardCharsets.UTF_8);

        storage.put("photos/2026/test.jpg", new ByteArrayInputStream(content),
                content.length, "image/jpeg");

        assertThat(storage.stat("photos/2026/test.jpg"))
                .isEqualTo(new ObjectStorageService.ObjectInfo(content.length, "image/jpeg"));
        assertThat(storage.open("photos/2026/test.jpg").readAllBytes()).isEqualTo(content);

        ObjectStorageService.SignedUrl get = storage.presignGet(
                "photos/2026/test.jpg", "结果图.jpg", Duration.ofMinutes(1));
        String token = get.url().getPath().substring(get.url().getPath().lastIndexOf('/') + 1);
        assertThat(storage.resolveToken(token, "GET").objectKey()).isEqualTo("photos/2026/test.jpg");
        assertThatThrownBy(() -> storage.resolveToken(token, "PUT"))
                .isInstanceOf(IllegalArgumentException.class);

        storage.delete("photos/2026/test.jpg");
        assertThatThrownBy(() -> storage.stat("photos/2026/test.jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(directory.resolve("photos/2026/test.jpg.metadata")).doesNotExist();
        assertThat(directory.resolve("photos/2026/test.jpg.content-type")).doesNotExist();
    }

    @Test
    void storesNormalizedImmutableUserMetadataAndClearsItOnOverwrite() throws Exception {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects", "test-secret",
                java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        LocalObjectStorageService storage = new LocalObjectStorageService(properties);
        String key = "thumbnails/profile.jpg";
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);

        storage.put(key, new ByteArrayInputStream(first), first.length, "image/jpeg",
                Map.of("Preview-Compression-Ratio", "0.6", "Generator", "libvips-v1"));

        ObjectStorageService.ObjectInfo firstInfo = storage.stat(key);
        assertThat(firstInfo.userMetadata()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "preview-compression-ratio", "0.6",
                "generator", "libvips-v1"));
        assertThatThrownBy(() -> firstInfo.userMetadata().put("other", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(directory.resolve(key + ".metadata")).isRegularFile();
        assertThat(storage.list("thumbnails/"))
                .extracting(ObjectStorageService.StoredObject::objectKey)
                .containsExactly(key);

        Files.writeString(directory.resolve(key + ".content-type"), "stale/type",
                StandardCharsets.UTF_8);
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        storage.put(key, new ByteArrayInputStream(replacement), replacement.length, "image/jpeg");

        assertThat(storage.stat(key).userMetadata()).isEmpty();
        assertThat(storage.open(key).readAllBytes()).isEqualTo(replacement);
        assertThat(directory.resolve(key + ".content-type")).doesNotExist();
    }

    @Test
    void objectInfoRejectsUserMetadataKeysThatCollideIgnoringCase() {
        assertThatThrownBy(() -> new ObjectStorageService.ObjectInfo(1, "image/jpeg",
                Map.of("Profile", "one", "profile", "two")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("profile");
    }

    @Test
    void rejectsPathTraversal() {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects", "test-secret",
                java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        LocalObjectStorageService storage = new LocalObjectStorageService(properties);

        assertThatThrownBy(() -> storage.put("../outside", new ByteArrayInputStream(new byte[0]),
                0, "application/octet-stream")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.list("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listsOnlyObjectsUnderTheDeterminablePrefixDirectory() throws Exception {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects", "test-secret",
                java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        LocalObjectStorageService storage = new LocalObjectStorageService(properties);
        byte[] thumbnail = "thumbnail".getBytes(StandardCharsets.UTF_8);
        byte[] photo = "photo".getBytes(StandardCharsets.UTF_8);
        storage.put("thumbnails/generations/one.jpg", new ByteArrayInputStream(thumbnail),
                thumbnail.length, "image/jpeg");
        storage.put("photos/one.jpg", new ByteArrayInputStream(photo), photo.length, "image/jpeg");

        assertThat(storage.list("thumbnails/"))
                .extracting(ObjectStorageService.StoredObject::objectKey)
                .containsExactly("thumbnails/generations/one.jpg");
        assertThat(storage.list("thumbnails/generations/one.jpg"))
                .containsExactly(new ObjectStorageService.StoredObject(
                        "thumbnails/generations/one.jpg", thumbnail.length));
        assertThat(storage.find("thumbnails/generations/one.jpg"))
                .contains(new ObjectStorageService.ObjectInfo(thumbnail.length, "image/jpeg"));
        assertThat(storage.find("thumbnails/generations/missing.jpg")).isEmpty();
        assertThat(storage.list("missing-prefix/")).isEmpty();
        assertThat(Files.isRegularFile(directory.resolve("photos/one.jpg"))).isTrue();
    }

    @Test
    void listIgnoresInFlightTemporaryUploads() throws Exception {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects", "test-secret",
                java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        LocalObjectStorageService storage = new LocalObjectStorageService(properties);
        Path generation = Files.createDirectories(directory.resolve("thumbnails/generations"));
        Files.write(generation.resolve(".upload-in-progress.tmp"), new byte[]{1, 2, 3});

        assertThat(storage.list("thumbnails/")).isEmpty();
    }

    @Test
    void recoversImageContentTypeFromMagicWhenSidecarIsMissingOrWrong() throws Exception {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects", "test-secret",
                java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        LocalObjectStorageService storage = new LocalObjectStorageService(properties);
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        String key = "thumbnails/legacy.jpg";
        storage.put(key, new ByteArrayInputStream(jpeg), jpeg.length, "application/octet-stream");

        assertThat(storage.stat(key).contentType()).isEqualTo("image/jpeg");
        Files.delete(directory.resolve(key + ".metadata"));
        Files.writeString(directory.resolve(key + ".content-type"), "application/octet-stream",
                StandardCharsets.UTF_8);
        assertThat(storage.stat(key).contentType()).isEqualTo("image/jpeg");
        assertThat(storage.list("thumbnails/"))
                .extracting(ObjectStorageService.StoredObject::objectKey)
                .containsExactly(key);
    }

    @Test
    void readsAndDeletesLegacyContentTypeSidecar() throws Exception {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects", "test-secret",
                java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);
        LocalObjectStorageService storage = new LocalObjectStorageService(properties);
        String key = "legacy/note.txt";
        byte[] content = "legacy".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(directory.resolve("legacy"));
        Files.write(directory.resolve(key), content);
        Files.writeString(directory.resolve(key + ".content-type"), "text/plain",
                StandardCharsets.UTF_8);

        assertThat(storage.stat(key))
                .isEqualTo(new ObjectStorageService.ObjectInfo(content.length, "text/plain"));
        assertThat(storage.list("legacy/"))
                .extracting(ObjectStorageService.StoredObject::objectKey)
                .containsExactly(key);

        storage.delete(key);
        assertThat(directory.resolve(key)).doesNotExist();
        assertThat(directory.resolve(key + ".content-type")).doesNotExist();
    }

    @Test
    void limitedUploadStreamStopsChunkedBodiesAtConfiguredLimit() throws Exception {
        LimitedInputStream input = new LimitedInputStream(
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4, 5}), 4);

        assertThatThrownBy(input::readAllBytes)
                .isInstanceOf(UploadSizeLimitExceededException.class);
    }

    @Test
    void configValidatorRejectsRepositoryDevelopmentSecret() {
        StorageProperties properties = new StorageProperties(
                "local", null, null, null, null, null, directory.toString(),
                "http://localhost:8080/api/v1/local-storage/objects",
                "photolib-local-development-secret", java.util.List.of("*"),
                Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
                10_485_760, 104_857_600, 0.6);

        assertThatThrownBy(() -> new StorageConfigValidator(properties).validateConfig())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("弱签名密钥");
    }
}
