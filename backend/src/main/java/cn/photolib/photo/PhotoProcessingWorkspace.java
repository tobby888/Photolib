package cn.photolib.photo;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

@Component
public class PhotoProcessingWorkspace {
    private final Path root;
    private final Path batchesRoot;
    private final Path tasksRoot;

    public PhotoProcessingWorkspace(PhotoProcessingProperties properties) {
        this.root = properties.temporaryRoot();
        this.batchesRoot = root.resolve("batches");
        this.tasksRoot = root.resolve("tasks");
        try {
            Files.createDirectories(batchesRoot);
            Files.createDirectories(tasksRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化图片处理临时目录: " + root, exception);
        }
    }

    public Path createBatchFile(String batchId, String extension) throws IOException {
        if (batchId == null || !batchId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("批次 ID 不能用于本地临时路径");
        }
        Path directory = requireManaged(batchesRoot.resolve(batchId));
        Files.createDirectories(directory);
        return requireManaged(directory.resolve(UUID.randomUUID() + normalizeExtension(extension)));
    }

    public Path createTaskDirectory(long photoId) throws IOException {
        Files.createDirectories(tasksRoot);
        return requireManaged(Files.createTempDirectory(tasksRoot, "photo-" + photoId + "-"));
    }

    public Path taskFile(Path taskDirectory, String fileName) {
        return requireManaged(taskDirectory.resolve(fileName));
    }

    public Path resolveStoredPath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("图片本地临时路径为空");
        }
        return requireManaged(Path.of(value));
    }

    public void deleteBatchFile(Path file) {
        Path managed = requireManaged(file);
        try {
            Files.deleteIfExists(managed);
            Path batchDirectory = managed.getParent();
            if (batchDirectory != null && batchDirectory.getParent() != null
                    && batchDirectory.getParent().equals(batchesRoot)) {
                try {
                    Files.deleteIfExists(batchDirectory);
                } catch (DirectoryNotEmptyException ignored) {
                    // Other entries from this batch are still waiting or processing.
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("清理批次临时文件失败: " + managed, exception);
        }
    }

    public void deleteRecursively(Path target) {
        Path managed = requireManaged(target);
        if (!Files.exists(managed)) return;
        try (Stream<Path> paths = Files.walk(managed)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("清理图片处理临时目录失败: " + managed, exception);
        }
    }

    Path root() {
        return root;
    }

    private Path requireManaged(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IllegalArgumentException("拒绝访问图片处理临时目录之外的路径");
        }
        return normalized;
    }

    private String normalizeExtension(String extension) {
        if (extension == null || !extension.matches("\\.[a-z0-9]{1,8}")) {
            throw new IllegalArgumentException("无效的图片临时文件扩展名");
        }
        return extension;
    }
}
