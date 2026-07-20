package cn.photolib.photo;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class ImageCompressor {
    static final long MAX_PIXELS = 100_000_000L;
    static final int MAX_DIMENSION = 30_000;

    private final NativeImageProcessor nativeProcessor;

    public ImageCompressor() {
        this(NativeImageProcessor.instance());
    }

    ImageCompressor(NativeImageProcessor nativeProcessor) {
        this.nativeProcessor = nativeProcessor;
    }

    public FileResult compress(Path source, Path destination, String contentType,
                               long targetBytes) throws IOException {
        if (targetBytes <= 0) {
            throw new IllegalArgumentException("目标图片大小必须大于 0");
        }
        requireDistinctPaths(source, destination);
        NativeImageProcessor.Dimensions dimensions = nativeProcessor.dimensions(source, contentType);
        if (Files.size(source) <= targetBytes) {
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return new FileResult(destination, Files.size(destination), dimensions.width(),
                    dimensions.height(), contentType);
        }
        NativeImageProcessor.ProcessedFile processed = nativeProcessor.compress(
                source, destination, contentType, targetBytes);
        validateOutput(processed);
        return new FileResult(processed.path(), processed.length(), processed.width(),
                processed.height(), contentType);
    }

    public FileResult thumbnail(Path source, Path destination, String contentType,
                                int maxDimension, double compressionRatio) throws IOException {
        if (compressionRatio <= 0 || compressionRatio > 1) {
            throw new IllegalArgumentException("预览图压缩比率必须大于 0 且不超过 1");
        }
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("预览图最大边长必须大于 0");
        }
        requireDistinctPaths(source, destination);
        NativeImageProcessor.ProcessedFile processed = nativeProcessor.thumbnail(
                source, destination, contentType, maxDimension, compressionRatio);
        validateOutput(processed);
        return new FileResult(processed.path(), processed.length(), processed.width(),
                processed.height(), contentType);
    }

    private void requireDistinctPaths(Path source, Path destination) throws IOException {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedDestination)) {
            throw new IllegalArgumentException("原图和输出图片路径不能相同");
        }
        Path parent = normalizedDestination.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("输出图片路径缺少父目录");
        }
        Files.createDirectories(parent);
    }

    private void validateOutput(NativeImageProcessor.ProcessedFile processed) throws IOException {
        long actual = Files.size(processed.path());
        if (actual != processed.length()) {
            throw new IOException("原生图片输出大小不一致");
        }
    }

    public record FileResult(Path path, long size, int width, int height, String contentType) {
    }
}
