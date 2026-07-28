package cn.photolib.photo;

import com.sun.jna.Library;
import com.sun.jna.Native;
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

    Dimensions dimensions(Path source, String contentType) throws IOException {
        NativeDimensions result = new NativeDimensions();
        int status = library.photolib_dimensions_file(nativePath(source), format(contentType), result);
        result.read();
        if (status != 0) {
            throw failure(result.errorMessage);
        }
        return new Dimensions(result.width, result.height);
    }

    ProcessedFile compress(Path source, Path destination, String contentType,
                           long targetBytes) throws IOException {
        return processFile(source, destination, contentType, OP_COMPRESS,
                targetBytes, 0, 0);
    }

    ProcessedFile thumbnail(Path source, Path destination, String contentType,
                            int maxDimension, double quality) throws IOException {
        return processFile(source, destination, contentType, OP_THUMBNAIL,
                0, maxDimension, quality);
    }

    private ProcessedFile processFile(Path source, Path destination, String contentType,
                                      int operation, long targetBytes,
                                      int maxDimension, double quality) throws IOException {
        NativeFileResult result = new NativeFileResult();
        int status = library.photolib_process_file(nativePath(source), nativePath(destination),
                format(contentType), operation, targetBytes, maxDimension, quality, result);
        result.read();
        if (status != 0) {
            throw failure(result.errorMessage);
        }
        if (result.length <= 0 || !Files.isRegularFile(destination)) {
            throw new IOException("原生图片处理器未生成有效的本地文件");
        }
        return new ProcessedFile(destination, result.length, result.width, result.height);
    }

    private byte[] nativePath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("本地图片路径包含非法字符");
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        byte[] terminated = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        terminated[encoded.length] = 0;
        return terminated;
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
        try {
            Path directory = Files.createTempDirectory("photolib-image-");
            directory.toFile().deleteOnExit();
            Path vipsPath = extractResource(directory, platform.vipsResourcePath(),
                    platform.vipsFileName());
            System.load(vipsPath.toAbsolutePath().toString());
            Path wrapperPath = extractResource(directory, platform.wrapperResourcePath(),
                    platform.wrapperFileName());
            return Native.load(wrapperPath.toAbsolutePath().toString(), NativeLibrary.class);
        } catch (IOException | UnsatisfiedLinkError ex) {
            throw new IllegalStateException("无法加载当前平台的原生图片组件", ex);
        }
    }

    private static Path extractResource(Path directory, String resourcePath,
                                        String fileName) throws IOException {
        try (InputStream input = NativeImageProcessor.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Fat JAR 中缺少原生图片组件: " + resourcePath);
            }
            Path extracted = directory.resolve(fileName);
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            return extracted;
        }
    }

    record Dimensions(int width, int height) {
    }

    record ProcessedFile(Path path, long length, int width, int height) {
    }

    @Structure.FieldOrder({"width", "height", "channels", "errorMessage"})
    public static final class NativeDimensions extends Structure {
        public int width;
        public int height;
        public int channels;
        public byte[] errorMessage = new byte[ERROR_CAPACITY];
    }

    @Structure.FieldOrder({"length", "width", "height", "errorMessage"})
    public static final class NativeFileResult extends Structure {
        public long length;
        public int width;
        public int height;
        public byte[] errorMessage = new byte[ERROR_CAPACITY];
    }

    private interface NativeLibrary extends Library {
        int photolib_dimensions_file(byte[] inputPath, int format, NativeDimensions output);

        int photolib_process_file(byte[] inputPath, byte[] outputPath, int format, int operation,
                                  long targetBytes, int maxDimension, double quality,
                                  NativeFileResult output);
    }

    private record PlatformResource(String wrapperResourcePath, String wrapperFileName,
                                    String vipsResourcePath, String vipsFileName) {
        private static PlatformResource detect() {
            String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (!architecture.equals("amd64") && !architecture.equals("x86_64")) {
                throw new IllegalStateException("原生图片组件仅支持 x86-64，当前架构: " + architecture);
            }
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                return new PlatformResource(
                        "/native/windows-x86_64/photolib-image.dll", "photolib-image.dll",
                        "/native/windows-x86_64/libvips-42.dll", "libvips-42.dll");
            }
            if (os.contains("linux")) {
                return new PlatformResource(
                        "/native/linux-x86_64/libphotolib-image.so", "libphotolib-image.so",
                        "/native/linux-x86_64/libvips-cpp.so.8.18.3",
                        "libvips-cpp.so.8.18.3");
            }
            throw new IllegalStateException("原生图片组件不支持当前操作系统: " + os);
        }
    }

    private static final class Holder {
        private static final NativeImageProcessor INSTANCE = new NativeImageProcessor(loadLibrary());
    }
}
