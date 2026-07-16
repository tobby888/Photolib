package cn.photolib.photo;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

final class NativeImageProcessor {
    private static final int FORMAT_JPEG = 1;
    private static final int FORMAT_PNG = 2;
    private static final int OP_COMPRESS = 1;
    private static final int OP_THUMBNAIL = 2;
    private static final int ERROR_CAPACITY = 256;

    private final NativeLibrary library;

    private NativeImageProcessor(NativeLibrary library) {
        this.library = library;
    }

    static NativeImageProcessor instance() {
        return Holder.INSTANCE;
    }

    Dimensions dimensions(byte[] source, String contentType) throws IOException {
        NativeDimensions result = new NativeDimensions();
        int status = library.photolib_dimensions(source, source.length, format(contentType), result);
        result.read();
        if (status != 0) {
            throw failure(result.errorMessage);
        }
        return new Dimensions(result.width, result.height);
    }

    ProcessedImage compress(byte[] source, String contentType, long targetBytes) throws IOException {
        return process(source, contentType, OP_COMPRESS, targetBytes, 0, 0);
    }

    ProcessedImage thumbnail(byte[] source, String contentType, int maxDimension,
                             double quality) throws IOException {
        return process(source, contentType, OP_THUMBNAIL, 0, maxDimension, quality);
    }

    private ProcessedImage process(byte[] source, String contentType, int operation,
                                   long targetBytes, int maxDimension, double quality) throws IOException {
        NativeResult result = new NativeResult();
        int status = library.photolib_process(source, source.length, format(contentType), operation,
                targetBytes, maxDimension, quality, result);
        result.read();
        if (status != 0) {
            throw failure(result.errorMessage);
        }
        if (result.data == null || result.length <= 0 || result.length > Integer.MAX_VALUE) {
            if (result.data != null) {
                library.photolib_free(result.data);
            }
            throw new IOException("原生图片处理器返回了无效结果");
        }
        try {
            return new ProcessedImage(result.data.getByteArray(0, (int) result.length),
                    result.width, result.height);
        } finally {
            library.photolib_free(result.data);
        }
    }

    private int format(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> FORMAT_JPEG;
            case "image/png" -> FORMAT_PNG;
            default -> throw new IllegalArgumentException("不支持的图片类型: " + contentType);
        };
    }

    private IOException failure(byte[] errorMessage) {
        int length = 0;
        while (length < errorMessage.length && errorMessage[length] != 0) {
            length++;
        }
        String detail = length == 0 ? "unknown native error"
                : new String(errorMessage, 0, length, StandardCharsets.UTF_8);
        return new IOException("原生图片处理失败: " + detail);
    }

    private static NativeLibrary loadLibrary() {
        PlatformResource platform = PlatformResource.detect();
        try (InputStream input = NativeImageProcessor.class.getResourceAsStream(platform.resourcePath())) {
            if (input == null) {
                throw new IllegalStateException("Fat JAR 中缺少原生图片组件: " + platform.resourcePath());
            }
            Path directory = Files.createTempDirectory("photolib-image-");
            Path libraryPath = directory.resolve(platform.fileName());
            Files.copy(input, libraryPath, StandardCopyOption.REPLACE_EXISTING);
            directory.toFile().deleteOnExit();
            libraryPath.toFile().deleteOnExit();
            return Native.load(libraryPath.toAbsolutePath().toString(), NativeLibrary.class);
        } catch (IOException | UnsatisfiedLinkError ex) {
            throw new IllegalStateException("无法加载当前平台的原生图片组件", ex);
        }
    }

    record Dimensions(int width, int height) {
    }

    record ProcessedImage(byte[] bytes, int width, int height) {
    }

    @Structure.FieldOrder({"width", "height", "channels", "errorMessage"})
    public static final class NativeDimensions extends Structure {
        public int width;
        public int height;
        public int channels;
        public byte[] errorMessage = new byte[ERROR_CAPACITY];
    }

    @Structure.FieldOrder({"data", "length", "width", "height", "errorMessage"})
    public static final class NativeResult extends Structure {
        public Pointer data;
        public long length;
        public int width;
        public int height;
        public byte[] errorMessage = new byte[ERROR_CAPACITY];
    }

    private interface NativeLibrary extends Library {
        int photolib_dimensions(byte[] input, long inputLength, int format, NativeDimensions output);

        int photolib_process(byte[] input, long inputLength, int format, int operation,
                             long targetBytes, int maxDimension, double quality, NativeResult output);

        void photolib_free(Pointer pointer);
    }

    private record PlatformResource(String resourcePath, String fileName) {
        private static PlatformResource detect() {
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (!architecture.equals("amd64") && !architecture.equals("x86_64")) {
                throw new IllegalStateException("原生图片组件仅支持 x86-64，当前架构: " + architecture);
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return new PlatformResource(
                        "/native/windows-x86_64/photolib-image.dll", "photolib-image.dll");
            }
            if (os.contains("linux")) {
                return new PlatformResource(
                        "/native/linux-x86_64/libphotolib-image.so", "libphotolib-image.so");
            }
            throw new IllegalStateException("原生图片组件不支持当前操作系统: " + os);
        }
    }

    private static final class Holder {
        private static final NativeImageProcessor INSTANCE = new NativeImageProcessor(loadLibrary());
    }
}
