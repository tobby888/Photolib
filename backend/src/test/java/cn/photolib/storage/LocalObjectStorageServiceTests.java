package cn.photolib.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

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
    }
}
