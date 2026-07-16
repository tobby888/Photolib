package cn.photolib.photo;

import org.springframework.stereotype.Component;

import java.io.IOException;

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

    public Result compress(byte[] source, String contentType, long targetBytes) throws IOException {
        if (targetBytes <= 0) {
            throw new IllegalArgumentException("目标图片大小必须大于 0");
        }
        NativeImageProcessor.Dimensions dimensions = nativeProcessor.dimensions(source, contentType);
        if (source.length <= targetBytes) {
            return new Result(source, dimensions.width(), dimensions.height(), contentType);
        }
        NativeImageProcessor.ProcessedImage processed = nativeProcessor.compress(
                source, contentType, targetBytes);
        return new Result(processed.bytes(), processed.width(), processed.height(), contentType);
    }

    public Result thumbnail(byte[] source, String contentType, int maxDimension) throws IOException {
        return thumbnail(source, contentType, maxDimension, 0.82);
    }

    public Result thumbnail(byte[] source, String contentType, int maxDimension,
                            double compressionRatio) throws IOException {
        if (compressionRatio <= 0 || compressionRatio > 1) {
            throw new IllegalArgumentException("预览图压缩比率必须大于 0 且不超过 1");
        }
        if (maxDimension <= 0) {
            throw new IllegalArgumentException("预览图最大边长必须大于 0");
        }
        NativeImageProcessor.ProcessedImage processed = nativeProcessor.thumbnail(
                source, contentType, maxDimension, compressionRatio);
        return new Result(processed.bytes(), processed.width(), processed.height(), contentType);
    }

    public record Result(byte[] bytes, int width, int height, String contentType) {
    }
}
